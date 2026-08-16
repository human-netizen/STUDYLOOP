package com.studyloop.backend.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 10's two guardrails, through the API.
//
// The budget half is driven from hand-written ledger rows rather than by spending real tokens:
// what is under test is the decision, not the provider. The rate-limit half has to be driven for
// real, because the thing worth proving is that the count the configuration promises is the count
// the app enforces.
//
// One asymmetry to know about: the ledger rows roll back with the test transaction, but the rate
// limiter is in memory and does not. Every test therefore uses its own freshly created user, whose
// bucket no other test has touched.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class QuotaTest {

    // Mirrors studyloop.quota in application.yml. Hard-coded rather than injected on purpose: a
    // test that read the same property the code reads would still pass if both were wrong.
    private static final long BUDGET_TOKENS = 200_000;
    private static final int UPLOADS_PER_HOUR = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User student;
    private String token;
    private String courseId;

    @BeforeEach
    void createStudentWithACourse() throws Exception {
        chatClient.reset();
        student = saveUser();
        token = jwtService.generateAccessToken(student);
        courseId = createCourse();
    }

    // ── the token budget ────────────────────────────────────────────────────────────────────

    @Test
    void aStudentWhoHasSpentTheAllowanceCannotStartAnotherExpensiveRequest() throws Exception {
        spend(student, BUDGET_TOKENS, Instant.now());

        ask().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("TOKEN_BUDGET"))
                .andExpect(jsonPath("$.usedTokens").value(BUDGET_TOKENS))
                .andExpect(jsonPath("$.limitTokens").value(BUDGET_TOKENS));
    }

    // The point of the guard is not the status code, it is that the provider is never called. A
    // budget enforced after the model has already answered would be a report, not a limit.
    @Test
    void theRefusalHappensBeforeTheModelIsAsked() throws Exception {
        spend(student, BUDGET_TOKENS, Instant.now());

        ask().andExpect(status().isTooManyRequests());

        assertEquals(0, chatClient.calls.get(), "nothing should have reached the provider");
    }

    // A spent budget does not clear in seconds like a rate limit, so "try again later" is useless
    // without a number. It comes from the oldest call still inside the window ageing out of it —
    // an hour spent means 23 to wait, not a flat 24.
    @Test
    void theRefusalSaysWhenTheAllowanceComesBack() throws Exception {
        spend(student, BUDGET_TOKENS, Instant.now().minus(1, ChronoUnit.HOURS));

        String retryAfter = ask().andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        long seconds = Long.parseLong(retryAfter);
        assertTrue(seconds > 22 * 3600 && seconds <= 23 * 3600,
                () -> "expected about 23 hours, got " + seconds + "s");
    }

    // The allowance is per person. A course full of students must not be locked out because one of
    // them ran a script.
    @Test
    void oneStudentSpendingTheirAllowanceDoesNotStopAnother() throws Exception {
        spend(student, BUDGET_TOKENS, Instant.now());
        User classmate = saveUser();
        join(classmate);

        mockMvc.perform(get(searchUrl()).param("q", "recursion")
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(classmate)))
                .andExpect(status().isOk());
    }

    // Rolling window: spend ages out gradually rather than everyone resetting at one midnight.
    @Test
    void spendingOlderThanTheWindowNoLongerCounts() throws Exception {
        spend(student, BUDGET_TOKENS * 5, Instant.now().minus(25, ChronoUnit.HOURS));

        ask().andExpect(status().isOk());
    }

    // Reading costs nothing, so being over budget must not lock someone out of the material they
    // already have. Only the endpoints that call a provider are gated.
    @Test
    void beingOverBudgetDoesNotBlockThePagesThatCostNothing() throws Exception {
        spend(student, BUDGET_TOKENS, Instant.now());

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ── the rate limit ──────────────────────────────────────────────────────────────────────
    //
    // Counted against the upload bucket rather than the question one, because its window is an
    // hour: over the seconds these tests take, refill is a rounding error, and the count they
    // assert is the count the configuration promises. Proving the same thing against a
    // per-minute allowance would mean 21 round trips racing a bucket that refills as they run —
    // a test that passes or fails depending on how the database is feeling. The arithmetic itself,
    // including refill, is pinned down in RateLimiterTest with a clock it controls.

    @Test
    void theAllowanceIsHonouredExactlyAndThenTheNextRequestIsRefused() throws Exception {
        for (int i = 0; i < UPLOADS_PER_HOUR; i++) {
            assertNotEquals(429, tryUpload(), "upload " + (i + 1) + " is within the allowance");
        }

        mockMvc.perform(uploadRequest(token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.limit").value(UPLOADS_PER_HOUR))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    // The limit is per person. One student uploading a term's worth of slides in a burst must not
    // stop the rest of the class adding theirs.
    @Test
    void oneStudentBurningTheirAllowanceDoesNotSlowAnother() throws Exception {
        exhaustUploads();
        User classmate = saveUser();
        join(classmate);

        int status = mockMvc.perform(uploadRequest(jwtService.generateAccessToken(classmate)))
                .andReturn().getResponse().getStatus();

        assertNotEquals(429, status, "another student's bucket is their own");
    }

    // Two buckets, not one shared limit: an upload is worth roughly a hundred questions in
    // provider calls, so counting both against the same allowance would make one of them useless.
    @Test
    void questionsAndUploadsDrawOnSeparateAllowances() throws Exception {
        exhaustUploads();

        search().andExpect(status().isOk());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private ResultActions ask() throws Exception {
        return mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ChatBody("What is recursion?", null))));
    }

    private ResultActions search() throws Exception {
        return mockMvc.perform(get(searchUrl()).param("q", "recursion")
                .header("Authorization", "Bearer " + token));
    }

    private String searchUrl() {
        return "/api/v1/courses/" + courseId + "/search";
    }

    // A multipart upload with no file part. It never reaches the controller, which is the point:
    // the guard runs in preHandle, so a request that is later rejected for any other reason has
    // still spent its token. A limit that only counted requests the app went on to accept could
    // be sidestepped by sending rubbish.
    private MockMultipartHttpServletRequestBuilder uploadRequest(String asToken) {
        MockMultipartHttpServletRequestBuilder request =
                multipart("/api/v1/courses/" + courseId + "/documents");
        request.header("Authorization", "Bearer " + asToken);
        return request;
    }

    private int tryUpload() throws Exception {
        return mockMvc.perform(uploadRequest(token)).andReturn().getResponse().getStatus();
    }

    private void exhaustUploads() throws Exception {
        for (int i = 0; i <= UPLOADS_PER_HOUR; i++) {
            tryUpload();
        }
    }

    // A ledger row for a user, as if a provider call of that size had been made and paid for.
    private void spend(User user, long tokens, Instant occurredAt) {
        jdbcTemplate.update("""
                insert into ai_usage_events
                    (id, occurred_at, provider, model, operation, user_id, input_tokens,
                     output_tokens, cost_usd)
                values (?, ?, 'cohere', 'test-model', 'CHAT', ?, ?, 0, 0)
                """,
                UUID.randomUUID(), Timestamp.from(occurredAt), user.getId(), tokens);
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("quota-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Quota Tester");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // Adds a second person to the course the direct way. Going through an invite would test the
    // invite flow, which has its own suite.
    private void join(User classmate) {
        jdbcTemplate.update("""
                insert into memberships (id, course_space_id, user_id, role)
                values (?, cast(? as uuid), ?, 'MEMBER')
                """,
                UUID.randomUUID(), courseId, classmate.getId());
    }

    private record ChatBody(String question, UUID conversationId) { }
}
