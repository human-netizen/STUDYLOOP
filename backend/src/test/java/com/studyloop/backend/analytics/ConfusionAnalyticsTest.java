package com.studyloop.backend.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.document.VectorSupport;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Confusion analytics (Phase 9.1). Two kinds of test here, because the feature has two halves
// that fail in completely different ways.
//
// The wiring half goes through the chat API: a question asked has to leave a row behind, a
// refused question has to be recorded *as* refused and credited to no lecture, and a question
// served from the semantic cache still has to count. That last one is the trap — cache hits
// return before retrieval runs, so it is exactly the path where logging quietly gets skipped.
//
// The algorithm half seeds vectors directly. StubAiConfig deliberately embeds different strings
// to near-orthogonal vectors, which is what makes the cache tests honest but leaves no way to
// write "two differently worded questions about the same thing" through the API. Constructing
// the vectors by hand is the only way to assert that the threshold actually groups and separates.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class ConfusionAnalyticsTest {

    private static final String ON_TOPIC = "What is dynamic programming?";
    private static final String OFF_TOPIC = "Which airline flies to Reykjavik?";
    private static final int DIMENSIONS = 768;

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

    private User owner;
    private String token;
    private String courseId;
    private String documentId;

    @BeforeEach
    void createCourseWithMaterial() throws Exception {
        owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        documentId = ingestPdf("Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");
        chatClient.reset();
    }

    @Test
    void anAnsweredQuestionHeatsTheLectureItLandedOn() throws Exception {
        ask(ON_TOPIC, null);

        JsonNode report = confusion();

        assertEquals(1, report.get("totals").get("questionsAsked").asInt());
        assertEquals(0, report.get("totals").get("ungrounded").asInt());
        assertEquals(1, report.get("totals").get("distinctAskers").asInt());

        JsonNode lecture = lectureFor(report, documentId);
        assertEquals(1, lecture.get("questionCount").asInt(), "the question landed on this document");
        assertEquals(1, lecture.get("distinctAskers").asInt());
        assertEquals(1.0, lecture.get("share").asDouble(), 1e-9,
                "one lecture attributed means it holds the whole share");
    }

    // The refusal is the row worth having, and it must not be credited to a lecture. Retrieval
    // does hand back weak chunks when the gate refuses — attributing those would put heat on a
    // document that demonstrably could not answer the question, which is the one thing the
    // heatmap must never do.
    @Test
    void aRefusedQuestionIsRecordedButCreditedToNoLecture() throws Exception {
        String answer = ask(OFF_TOPIC, null);
        assertTrue(answer.startsWith("I don't have that"), () -> "precondition: the gate refused, got: " + answer);

        JsonNode report = confusion();

        assertEquals(1, report.get("totals").get("questionsAsked").asInt());
        assertEquals(1, report.get("totals").get("ungrounded").asInt());
        assertEquals(1.0, report.get("totals").get("ungroundedRate").asDouble(), 1e-9);

        assertEquals(0, lectureFor(report, documentId).get("questionCount").asInt(),
                "a refused question must not heat the lecture it nearly matched");

        JsonNode gaps = report.get("ungrounded");
        assertEquals(1, gaps.size());
        assertEquals(OFF_TOPIC, gaps.get(0).get("question").asText());
        // No asker on the wire — the whole feature is anonymized by construction.
        assertTrue(gaps.get(0).get("askedBy") == null, "the report must not name who asked");
    }

    // A lecture nobody asks about is a finding, not an absence: either the class hasn't reached
    // it, or retrieval never surfaces it. Dropping the row would hide both.
    @Test
    void aLectureWithNoQuestionsStillAppears() throws Exception {
        JsonNode report = confusion();

        JsonNode lecture = lectureFor(report, documentId);
        assertNotNull(lecture, "every document in the course belongs on the heatmap");
        assertEquals(0, lecture.get("questionCount").asInt());
        assertEquals(0.0, lecture.get("share").asDouble(), 1e-9);
    }

    // The trap. A cache hit returns before retrieval runs, so it is the one path where the
    // question can silently go unrecorded — and a course whose students all ask the same popular
    // question would then report a fraction of its real volume.
    @Test
    void aQuestionServedFromTheCacheStillCounts() throws Exception {
        ask(ON_TOPIC, null);
        ask(ON_TOPIC, null);

        assertEquals(1, chatClient.calls.get(), "precondition: the second answer came from the cache");

        JsonNode report = confusion();
        assertEquals(2, report.get("totals").get("questionsAsked").asInt(),
                "both askings are questions the class asked, whoever paid for the answer");
        assertEquals(2, lectureFor(report, documentId).get("questionCount").asInt(),
                "the cached answer's citations carry the lecture attribution");
    }

    // Two students, one question each: the count that matters to an instructor is people, not
    // messages.
    @Test
    void distinctAskersAreCountedSeparatelyFromQuestions() throws Exception {
        User classmate = saveUser("classmate");
        addMember(classmate, "MEMBER");

        ask(ON_TOPIC, null);
        askAs(jwtService.generateAccessToken(classmate), ON_TOPIC);

        JsonNode report = confusion();
        assertEquals(2, report.get("totals").get("questionsAsked").asInt());
        assertEquals(2, report.get("totals").get("distinctAskers").asInt());
    }

    // The grouping itself, on vectors chosen so the answer is known in advance: two questions at
    // cosine 0.93 (above the 0.82 threshold) and one orthogonal to both.
    @Test
    void nearQuestionsGroupAndDistantOnesDoNot() throws Exception {
        float[] first = axis(0);
        float[] second = normalized(0, 1, 0.4f);   // cosine 0.93 with `first`
        float[] unrelated = axis(1);               // cosine 0.00 with `first`

        seedQuestion("How does memoization work?", first, true);
        seedQuestion("Explain how memoization caches results", second, true);
        seedQuestion("What is a red-black tree?", unrelated, false);

        JsonNode topics = confusion().get("topics");

        assertEquals(2, topics.size(), "the two near questions belong to one topic, the third to its own");
        assertEquals(2, topics.get(0).get("questionCount").asInt(), "biggest group first");
        assertEquals(1, topics.get(1).get("questionCount").asInt());
        assertEquals(1, topics.get(1).get("ungroundedCount").asInt(),
                "the lone question was refused, and its group should say so");
        // The label is one of the two real questions, never a synthesized name.
        String label = topics.get(0).get("label").asText();
        assertTrue(label.contains("memoization"), () -> "unexpected cluster label: " + label);
    }

    // Anonymized data is still teaching data. Showing a student that eleven classmates asked
    // their question changes how they behave, so the gate is the same one that guards uploads.
    @Test
    void aPlainMemberCannotSeeTheReport() throws Exception {
        User student = saveUser("student");
        addMember(student, "MEMBER");

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/analytics/confusion")
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(student)))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode confusion() throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/analytics/confusion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JsonNode lectureFor(JsonNode report, String documentId) {
        for (JsonNode lecture : report.get("lectures")) {
            if (lecture.get("documentId").asText().equals(documentId)) {
                return lecture;
            }
        }
        throw new AssertionError("document " + documentId + " is missing from the heatmap");
    }

    private String ask(String question, String conversationId) throws Exception {
        return chat(token, question, conversationId).get("answer").asText();
    }

    private void askAs(String otherToken, String question) throws Exception {
        chat(otherToken, question, null);
    }

    private JsonNode chat(String asToken, String question, String conversationId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, conversationId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    // Writes a question_event straight to the table, bypassing chat, so the vector is exactly
    // what the test chose. Grounded rows are attributed to the course's one document.
    private void seedQuestion(String question, float[] vector, boolean grounded) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into question_events
                    (id, course_space_id, asked_by, question, question_embedding, grounded, top_similarity)
                values (?, cast(? as uuid), ?, ?, cast(? as vector), ?, ?)
                """,
                eventId, courseId, owner.getId(), question, VectorSupport.toLiteral(vector),
                grounded, grounded ? 0.8 : 0.1);
        if (grounded) {
            jdbcTemplate.update("""
                    insert into question_event_documents (question_event_id, document_id)
                    values (?, cast(? as uuid))
                    """, eventId, documentId);
        }
    }

    // A unit vector along one axis.
    private static float[] axis(int index) {
        float[] vector = new float[DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

    // A unit vector leaning off `main` toward `other` by `weight`. Cosine against axis(main) is
    // 1/sqrt(1 + weight²), so 0.4 gives 0.93 — comfortably above the 0.82 clustering threshold
    // and comfortably below the 0.97 the semantic cache would call a paraphrase.
    private static float[] normalized(int main, int other, float weight) {
        float[] vector = new float[DIMENSIONS];
        float norm = (float) Math.sqrt(1.0 + weight * weight);
        vector[main] = 1.0f / norm;
        vector[other] = weight / norm;
        return vector;
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Analytics " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    // Straight into the table: the invite flow has its own tests, and going through it here would
    // make every analytics test depend on invites still working.
    private void addMember(User user, String role) {
        jdbcTemplate.update(
                "insert into memberships (id, course_space_id, user_id, role) values (?, cast(? as uuid), ?, ?)",
                UUID.randomUUID(), courseId, user.getId(), role);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String ingestPdf(String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        ingestionService.ingest(UUID.fromString(id));
        return id;
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
