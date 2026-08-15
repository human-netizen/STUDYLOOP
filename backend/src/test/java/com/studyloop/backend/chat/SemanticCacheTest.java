package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.security.JwtService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The semantic cache (Phase 8.3), tested the only way it can honestly be: by counting how often
// the provider was actually called. Every one of these tests would pass on a completely broken
// cache if it asserted on the *answer* alone — a cache that never fires still returns the right
// text, it just pays for it every time. The call counter is the whole assertion.
//
// StubAiConfig supplies both providers, so this shares its cached ApplicationContext with the
// document tests (see the note there about the pooler's client budget) and needs no API keys.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class SemanticCacheTest {

    // Deliberately shares vocabulary with the ingested PDF: the stub's embeddings are random per
    // string, so retrieval's semantic half is noise here and the confidence gate passes on the
    // lexical hit. That keeps these tests about the cache rather than about retrieval quality.
    private static final String QUESTION = "What is dynamic programming?";
    private static final String OTHER_QUESTION = "How does optimal substructure apply?";
    private static final String OFF_TOPIC = "Which airline flies to Reykjavik?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RecordingChatClient chatClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;

    @BeforeEach
    void createCourseWithMaterial() throws Exception {
        User owner = saveUser();
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse("Algorithms");
        ingestPdf("Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");

        // Ingestion ends by summarizing the document, which is itself a provider call. Reset here
        // so every count below is chat and nothing else.
        chatClient.reset();
    }

    @Test
    void repeatingAQuestionSkipsTheProviderEntirely() throws Exception {
        JsonNode first = chat(QUESTION, null);
        assertEquals(1, chatClient.calls.get(), "the first question has to be generated");

        JsonNode second = chat(QUESTION, null);

        assertEquals(1, chatClient.calls.get(),
                "asking the same question again should have been served from the cache");
        assertEquals(first.get("answer").asText(), second.get("answer").asText(),
                "a cache hit must return the answer it stored");
        assertEquals(1, cachedEntries(), "one question answered means one cache entry");
    }

    // Citations survive the round trip through the jsonb column. Worth its own test because the
    // service swallows a deserialization failure and returns an empty list — the answer would
    // still look perfect while every [n] marker in it pointed at nothing.
    @Test
    void aCachedAnswerKeepsItsSources() throws Exception {
        JsonNode generated = chat(QUESTION, null);
        JsonNode sources = generated.get("citations");
        assertTrue(sources.size() > 0, "precondition: the generated answer cited something");

        JsonNode cached = chat(QUESTION, null);

        assertEquals(1, chatClient.calls.get(), "precondition: the second answer came from the cache");
        assertEquals(sources.size(), cached.get("citations").size(), "a hit must carry its sources back");
        assertEquals(sources.get(0).get("filename").asText(),
                cached.get("citations").get(0).get("filename").asText());
        assertEquals(sources.get(0).get("chunkId").asText(),
                cached.get("citations").get(0).get("chunkId").asText());
        assertEquals(sources.get(0).get("snippet").asText(),
                cached.get("citations").get(0).get("snippet").asText());
    }

    // The cache keys on meaning, not on the string — but "meaning" has to have a limit, or every
    // question in a course collapses onto the first answer ever given.
    @Test
    void anUnrelatedQuestionStillReachesTheProvider() throws Exception {
        ask(QUESTION, null);
        chatClient.nextAnswer = "Optimal substructure means optimal solutions contain optimal subsolutions [1].";

        String second = ask(OTHER_QUESTION, null);

        assertEquals(2, chatClient.calls.get(), "a different question must not be served a stored answer");
        assertNotEquals(RecordingChatClient.DEFAULT_ANSWER, second);
        assertEquals(2, cachedEntries());
    }

    // The rule that makes the cache safe: "what about the second one?" means nothing without the
    // turns before it, so identical text in two different threads is not the same question.
    @Test
    void aFollowUpIsNeverAnsweredFromTheCache() throws Exception {
        JsonNode opening = chat(QUESTION, null);
        String conversationId = opening.get("conversationId").asText();
        assertEquals(1, chatClient.calls.get());

        // The very same words, this time inside the existing thread.
        ask(QUESTION, conversationId);

        assertEquals(2, chatClient.calls.get(),
                "a question inside a thread must be answered in context, never from the cache");
    }

    // A new document can change what any earlier answer should have said — most obviously by
    // making a previously refused question answerable.
    @Test
    void ingestingAnotherDocumentClearsTheCourseCache() throws Exception {
        ask(QUESTION, null);
        ask(QUESTION, null);
        assertEquals(1, chatClient.calls.get(), "precondition: the second ask was cached");

        ingestPdf("Memoization stores the result of each subproblem the first time it is solved.");
        chatClient.reset();

        ask(QUESTION, null);

        assertEquals(1, chatClient.calls.get(),
                "the cache should have been dropped when the course's materials changed");
        assertEquals(1, cachedEntries(), "only the answer generated after ingestion should remain");
    }

    // "I don't have that in the materials" is the answer most likely to be wrong tomorrow, so it
    // is the one answer the cache must not keep.
    @Test
    void aRefusalIsNotCached() throws Exception {
        String refusal = ask(OFF_TOPIC, null);

        assertTrue(refusal.startsWith("I don't have that"), () -> "expected the canned refusal, got: " + refusal);
        assertEquals(0, chatClient.calls.get(), "the confidence gate should have refused before the provider");
        assertEquals(0, cachedEntries(), "a refusal must not be remembered");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String ask(String question, String conversationId) throws Exception {
        return chat(question, conversationId).get("answer").asText();
    }

    // A null conversationId opens a new thread, which is the only shape the cache can serve.
    private JsonNode chat(String question, String conversationId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, conversationId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private int cachedEntries() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_cache_entries where course_space_id = cast(? as uuid)",
                Integer.class, courseId);
        return count == null ? 0 : count;
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("cache-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Cache Tester");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCourseRequest(name, "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // Upload and ingest in one step, synchronously — the async listener is bypassed so the test
    // can assert against a READY document without polling.
    private void ingestPdf(String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        ingestionService.ingest(UUID.fromString(objectMapper.readTree(body).get("id").asText()));
    }

    private byte[] pdfBytes(String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private record ChatBody(String question, String conversationId) { }
}
