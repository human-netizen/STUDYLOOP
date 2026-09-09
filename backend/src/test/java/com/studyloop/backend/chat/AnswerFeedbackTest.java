package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 28.3 — was this answer any good.
//
// **The citations are what is under test, not the verdict.** A thumbs-down that records only
// "wrong" is a row nobody can act on; a thumbs-down that records the passages the answer actually
// read separates the two failures that need opposite fixes. So the assertion that matters is that
// the stored citations are *the ones that were sent*, not the ones a fresh retrieval would return
// now — which is a distinction a test asserting "citations is non-empty" would miss entirely.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class AnswerFeedbackTest {

    private static final String QUESTION = "What is a treap?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;
    private User owner;

    @BeforeEach
    void createCourseWithMaterial() throws Exception {
        owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        ingestPdf("A treap is a binary search tree whose nodes also carry a random heap priority.");
    }

    // A grounded answer carries a handle for a verdict, and it is *not* the refusal handle — the
    // client tests that one for null to decide whether to offer "ask the class", so setting it on
    // every answer would put an escalation button under every correct answer in the product.
    @Test
    void aGroundedAnswerCarriesAFeedbackHandleButNoRefusalHandle() throws Exception {
        JsonNode answer = chat(QUESTION);

        assertFalse(answer.get("answerEventId").isNull(), "a logged answer must be reportable");
        assertTrue(answer.get("questionEventId").isNull(),
                "an answered question is not a refusal and must not offer the escalation");
    }

    // The assertion this class exists for.
    @Test
    void aThumbsDownRecordsTheCitationsThatWereSent() throws Exception {
        JsonNode answer = chat(QUESTION);
        JsonNode shown = answer.get("citations");
        assertTrue(shown.size() > 0, "precondition: the answer cited something");

        report(answer.get("answerEventId").asText(), false, "It missed the section on rotations.",
                shown);

        Map<String, Object> row = feedbackRow();
        assertEquals(false, row.get("helpful"));
        assertEquals("It missed the section on rotations.", row.get("reason"));
        assertEquals(QUESTION, row.get("question"));

        JsonNode stored = objectMapper.readTree((String) row.get("citations"));
        assertEquals(shown.size(), stored.size(), "every passage that was on screen has to be kept");
        assertEquals(shown.get(0).get("chunkId").asText(), stored.get(0).get("chunkId").asText(),
                "the stored citation must be the one that was sent, not one re-derived later");
        assertEquals(shown.get(0).get("snippet").asText(), stored.get(0).get("snippet").asText());
    }

    // One verdict per person per answer: clicking again changes your mind rather than stuffing the
    // ballot. The count is the assertion — the second row would be invisible from the API.
    @Test
    void asecondVerdictOnTheSameAnswerReplacesTheFirst() throws Exception {
        JsonNode answer = chat(QUESTION);
        String eventId = answer.get("answerEventId").asText();

        report(eventId, false, "wrong", answer.get("citations"));
        report(eventId, true, null, answer.get("citations"));

        assertEquals(1, feedbackCount(), "the same reader's second verdict must replace the first");
        assertEquals(true, feedbackRow().get("helpful"));
    }

    // A client-supplied id is checked against the course before it is stored, and a failing one is
    // dropped rather than rejected: the verdict keeps its question and its citations, which is the
    // actionable part, and a 400 would lose real feedback over a stale handle.
    @Test
    void anEventIdFromOutsideTheCourseIsStoredUnlinkedRatherThanRefused() throws Exception {
        JsonNode answer = chat(QUESTION);

        report(UUID.randomUUID().toString(), false, "wrong", answer.get("citations"));

        Map<String, Object> row = feedbackRow();
        assertNull(row.get("question_event_id"), "a foreign id must not be linked");
        assertEquals(QUESTION, row.get("question"), "the verdict itself is still recorded");
    }

    // The instructor's page is the only reader of this table, and it is a different section from
    // the ungrounded list: a refusal about uncovered material and a wrong answer about covered
    // material are opposite problems.
    @Test
    void theInstructorSeesReportedAnswersWithTheirPassages() throws Exception {
        JsonNode answer = chat(QUESTION);
        report(answer.get("answerEventId").asText(), false, "It missed the rotations.",
                answer.get("citations"));

        JsonNode report = confusion();

        assertEquals(1, report.get("unhelpfulVotes").asInt());
        assertEquals(0, report.get("helpfulVotes").asInt());
        assertEquals(1, report.get("reportedAnswers").size());
        JsonNode complaint = report.get("reportedAnswers").get(0);
        assertEquals(QUESTION, complaint.get("question").asText());
        assertEquals("It missed the rotations.", complaint.get("reason").asText());
        assertTrue(complaint.get("citations").size() > 0,
                "the passages are the point — a complaint without them is unactionable");
        assertNotNull(complaint.get("citations").get(0).get("filename").asText());
    }

    // A thumbs-up is counted and does not appear in the list. The denominator matters: three
    // reports out of four answers and three out of four hundred are not the same course.
    @Test
    void aThumbsUpCountsButIsNotListed() throws Exception {
        JsonNode answer = chat(QUESTION);
        report(answer.get("answerEventId").asText(), true, null, answer.get("citations"));

        JsonNode report = confusion();

        assertEquals(1, report.get("helpfulVotes").asInt());
        assertEquals(0, report.get("reportedAnswers").size(),
                "the list is the problems, not every verdict ever given");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void report(String answerEventId, boolean helpful, String reason, JsonNode citations)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "answerEventId", answerEventId,
                "helpful", helpful,
                "reason", reason == null ? "" : reason,
                "question", QUESTION,
                "citations", citations));
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private JsonNode confusion() throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/analytics/confusion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private Map<String, Object> feedbackRow() {
        return jdbcTemplate.queryForMap(
                "select question_event_id, helpful, reason, question, citations::text as citations "
                + "from answer_feedback where course_space_id = cast(? as uuid) "
                + "order by created_at desc limit 1", courseId);
    }

    private int feedbackCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from answer_feedback where course_space_id = cast(? as uuid)",
                Integer.class, courseId);
        return count == null ? 0 : count;
    }

    private JsonNode chat(String question) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody(question, null, List.of()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Feedback " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Data structures", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void ingestPdf(String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "treaps.pdf", "application/pdf", pdfBytes(lines)))
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

    private record ChatBody(String question, String conversationId, List<String> documentIds) { }
}
