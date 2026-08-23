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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The recurring-confusion header (Phase 20.3): the third time a student asks the same thing, chat
// says so — and tells the model to explain it differently rather than repeating itself.
//
// Two properties are worth more than the feature: it is per *student*, and it carries a count
// rather than a past answer. The first is what makes it a study aid instead of a class ranking;
// the second is what stops a wrong explanation from becoming this course's permanent position.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class RecurringQuestionTest {

    private static final String QUESTION = "What is dynamic programming?";

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
    private String studentToken;
    private String courseId;

    @BeforeEach
    void createCourseWithMaterialAndAStudent() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        ingestPdf("Dynamic programming solves problems by combining subproblem solutions.");

        User student = saveUser("student");
        addMember(student, "MEMBER");
        studentToken = jwtService.generateAccessToken(student);

        chatClient.reset();
    }

    // Not on the first ask, and not on the second either. The threshold is two *prior* questions,
    // because the first repeat is usually somebody rephrasing a question that was answered badly,
    // and telling them they have asked before is an accusation rather than a help.
    @Test
    void theFirstTwoAsksCarryNoHeader() throws Exception {
        assertTrue(chat(studentToken, QUESTION, null).get("askedBefore").isNull());
        assertTrue(chat(studentToken, QUESTION, null).get("askedBefore").isNull());
    }

    // The third one does, and it says what they asked and when.
    @Test
    void theThirdAskSaysSo() throws Exception {
        chat(studentToken, QUESTION, null);
        chat(studentToken, QUESTION, null);

        JsonNode askedBefore = chat(studentToken, QUESTION, null).get("askedBefore");

        assertEquals(2, askedBefore.get("times").asInt(), "the current turn is not counted");
        assertEquals(QUESTION, askedBefore.get("lastQuestion").asText());
        assertTrue(askedBefore.hasNonNull("lastAskedAt"));
    }

    // The model is told a number and nothing else — no earlier answer, no earlier phrasing of its
    // own. It is asked for a different explanation, which is the only thing the count can support.
    @Test
    void theModelIsAskedToExplainItDifferently() throws Exception {
        JsonNode first = chat(studentToken, QUESTION, null);
        chat(studentToken, QUESTION, null);
        chatClient.reset();

        // A follow-up inside the first conversation, so the semantic cache is out of the way and
        // the prompt is actually built — an opening question that repeats itself is served from
        // the cache without the model being called at all.
        JsonNode third = chat(studentToken, QUESTION, first.get("conversationId").asText());

        assertEquals(2, third.get("askedBefore").get("times").asInt());
        String prompt = chatClient.lastSystemPrompt();
        assertTrue(prompt.contains("2 times before"), () -> "the count should reach the model: " + prompt);
        assertTrue(prompt.contains("Explain it a different way"), prompt);
    }

    // Per student. Two people asking the same question is a topic for the instructor's page, not a
    // note in either of their chats — and a header that counted the class would tell each of them
    // something about the others.
    @Test
    void oneStudentsRepeatsAreNotAnothersHeader() throws Exception {
        chat(studentToken, QUESTION, null);
        chat(studentToken, QUESTION, null);
        chat(studentToken, QUESTION, null);

        User other = saveUser("other");
        addMember(other, "MEMBER");
        String otherToken = jwtService.generateAccessToken(other);

        assertTrue(chat(otherToken, QUESTION, null).get("askedBefore").isNull(),
                "this student has never asked it");
    }

    // A different question is a different question. The threshold is the clustering one, so the
    // header and the instructor's topic list can never disagree about what counts as the same ask.
    @Test
    void unrelatedQuestionsDoNotAccumulate() throws Exception {
        chat(studentToken, QUESTION, null);
        chat(studentToken, "How does memoization avoid recomputation?", null);

        assertTrue(chat(studentToken, "What is optimal substructure?", null).get("askedBefore").isNull());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode chat(String asToken, String question, String conversationId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, conversationId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Repeat " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private void addMember(User user, String role) {
        jdbcTemplate.update(
                "insert into memberships (id, course_space_id, user_id, role) values (?, cast(? as uuid), ?, ?)",
                UUID.randomUUID(), courseId, user.getId(), role);
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

    private void ingestPdf(String line) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", pdfBytes(line)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        ingestionService.ingest(UUID.fromString(objectMapper.readTree(body).get("id").asText()));
    }

    private byte[] pdfBytes(String line) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(line);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private record ChatBody(String question, String conversationId) { }
}
