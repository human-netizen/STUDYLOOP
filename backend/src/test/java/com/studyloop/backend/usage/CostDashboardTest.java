package com.studyloop.backend.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /admin/costs, driven from hand-written ledger rows rather than real provider calls — the
// arithmetic is what's under test, not Cohere.
//
// The ledger is global, so these tests clear it first and rely on the test transaction rolling
// back to put it right. That is safe precisely because it never commits: a developer's real
// usage history is untouched, but the assertions still get a table they know the contents of.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CostDashboardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String memberToken;

    @BeforeEach
    void emptyTheLedger() {
        jdbcTemplate.update("delete from ai_usage_events");
        jdbcTemplate.update("delete from chat_cache_entries");
        adminToken = jwtService.generateAccessToken(saveUser(Role.ADMIN));
        memberToken = jwtService.generateAccessToken(saveUser(Role.USER));
    }

    // Spend is an operator's business, and the per-feature breakdown is a fairly precise map of
    // how the app is used. Same rule as /admin/users.
    @Test
    void anOrdinaryUserCannotSeeTheCosts() throws Exception {
        mockMvc.perform(get("/api/v1/admin/costs").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/costs")).andExpect(status().isUnauthorized());
    }

    @Test
    void totalsAndTheBreakdownAddUpToWhatWasRecorded() throws Exception {
        record_(AiOperation.CHAT_STREAM, 1_000, 500, "0.000450", Instant.now());
        record_(AiOperation.CHAT_STREAM, 2_000, 400, "0.000540", Instant.now());
        record_(AiOperation.QUIZ_GENERATION, 8_000, 2_000, "0.002400", Instant.now());

        mockMvc.perform(get("/api/v1/admin/costs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(30))
                .andExpect(jsonPath("$.totals.calls").value(3))
                .andExpect(jsonPath("$.totals.inputTokens").value(11_000))
                .andExpect(jsonPath("$.totals.outputTokens").value(2_900))
                .andExpect(jsonPath("$.totals.costUsd").value(0.003390))
                // Most expensive feature first, which is the one worth doing something about.
                .andExpect(jsonPath("$.byOperation[0].operation").value("QUIZ_GENERATION"))
                .andExpect(jsonPath("$.byOperation[0].calls").value(1))
                .andExpect(jsonPath("$.byOperation[1].operation").value("CHAT_STREAM"))
                .andExpect(jsonPath("$.byOperation[1].calls").value(2))
                .andExpect(jsonPath("$.daily.length()").value(1));
    }

    @Test
    void spendOlderThanTheWindowIsExcluded() throws Exception {
        record_(AiOperation.CHAT, 1_000, 1_000, "0.000750", Instant.now().minus(60, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/v1/admin/costs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(0));

        mockMvc.perform(get("/api/v1/admin/costs?days=90")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(1));
    }

    // Two questions answered by the model, three served from the cache: a 60% hit rate, and five
    // answers delivered for the price of two.
    @Test
    void theCacheBlockReportsItsHitRateAndWhatItAvoided() throws Exception {
        String courseId = createCourse();
        record_(AiOperation.CHAT_STREAM, 1_000, 500, "0.001000", Instant.now());
        record_(AiOperation.CHAT_STREAM, 1_000, 500, "0.003000", Instant.now());
        cacheEntry(courseId, 2);
        cacheEntry(courseId, 1);

        mockMvc.perform(get("/api/v1/admin/costs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache.entries").value(2))
                .andExpect(jsonPath("$.cache.hits").value(3))
                .andExpect(jsonPath("$.cache.answersFromModel").value(2))
                .andExpect(jsonPath("$.cache.hitRate").value(0.6))
                // 3 hits × the $0.002 average of the two recorded answers.
                .andExpect(jsonPath("$.cache.estimatedSavedUsd").value(0.006));
    }

    // An empty ledger has to answer with zeroes, not a division by zero — this is the state the
    // dashboard is in the first time anyone opens it.
    @Test
    void anEmptyLedgerReportsZeroesRatherThanFailing() throws Exception {
        mockMvc.perform(get("/api/v1/admin/costs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(0))
                .andExpect(jsonPath("$.totals.costUsd").value(0))
                .andExpect(jsonPath("$.byOperation.length()").value(0))
                .andExpect(jsonPath("$.daily.length()").value(0))
                .andExpect(jsonPath("$.cache.hitRate").value(0.0))
                .andExpect(jsonPath("$.cache.estimatedSavedUsd").value(0.0));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    // Named with a trailing underscore because `record` is a contextual keyword and reads badly
    // as a method name next to the record declarations elsewhere in this codebase.
    private void record_(AiOperation operation, int inputTokens, int outputTokens, String costUsd,
                         Instant occurredAt) {
        jdbcTemplate.update("""
                insert into ai_usage_events
                    (id, occurred_at, provider, model, operation, input_tokens, output_tokens, cost_usd)
                values (?, ?, 'cohere', 'test-model', ?, ?, ?, cast(? as numeric))
                """,
                UUID.randomUUID(), Timestamp.from(occurredAt), operation.name(),
                inputTokens, outputTokens, costUsd);
    }

    private void cacheEntry(String courseId, int hitCount) {
        jdbcTemplate.update("""
                insert into chat_cache_entries
                    (id, course_space_id, question, question_embedding, answer, hit_count)
                values (?, cast(? as uuid), ?, cast(? as vector), 'cached answer', ?)
                """,
                UUID.randomUUID(), courseId, "question " + UUID.randomUUID(), unitVector(), hitCount);
    }

    // pgvector rejects a literal whose dimension doesn't match the column, and cosine distance is
    // undefined for the zero vector, so this is the cheapest thing that is valid on both counts.
    private static String unitVector() {
        StringBuilder literal = new StringBuilder(768 * 2 + 2).append("[1");
        for (int i = 1; i < 768; i++) {
            literal.append(",0");
        }
        return literal.append(']').toString();
    }

    private User saveUser(Role role) {
        User user = new User();
        user.setEmail("costs-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Costs Tester");
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCourseRequest("Costs", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
