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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The general-knowledge escape hatch (Phase 20.2): a refusal, then one explicit click, then an
// answer that says plainly it did not come from this course.
//
// The tests are about the boundary rather than the answer. An ungrounded answer is a liability
// unless three things hold — it is labelled, it never enters the corpus or the cache, and it is
// counted — and each of those is one test here.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class GeneralKnowledgeTest {

    private static final String OFF_TOPIC = "Which airline flies to Reykjavik?";
    private static final String GENERAL_ANSWER = "Icelandair and Play fly there from most of Europe.";

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

    @PersistenceContext
    private EntityManager entityManager;

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

    // The turn itself: a refusal, then the same question answered from outside the materials, with
    // no citations anywhere in the response — not an empty list, no field at all.
    @Test
    void aRefusedQuestionCanBeAnsweredFromGeneralKnowledge() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);
        chatClient.nextAnswer = GENERAL_ANSWER;

        JsonNode general = escalateToGeneral(refusal, OFF_TOPIC);

        assertEquals(GENERAL_ANSWER, general.get("answer").asText());
        assertFalse(general.has("citations"), "an ungrounded answer must not carry citation fields");
        assertEquals(refusal.get("conversationId").asText(), general.get("conversationId").asText(),
                "it is the same conversation, not a new one");
    }

    // The prompt is the label. It tells the model to say where the answer came from and — the part
    // that matters — forbids the [n] markers the rest of the product has trained readers to trust.
    @Test
    void thePromptForbidsTheCitationMarkersItHasNothingToPutBehind() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);

        escalateToGeneral(refusal, OFF_TOPIC);

        String prompt = chatClient.lastSystemPrompt();
        assertTrue(prompt.contains("general knowledge"), prompt);
        assertTrue(prompt.contains("Do NOT use citation markers"),
                () -> "the prompt must rule out fake citations: " + prompt);
    }

    // **The rule that keeps the corpus honest.** An answer the course did not produce must never
    // be served to the next person who asks the course something. The semantic cache is where that
    // would happen silently, so the same question asked again is refused exactly as before.
    @Test
    void theGeneralAnswerIsNotCachedAndTheGateStillRefusesTheQuestion() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);
        chatClient.nextAnswer = GENERAL_ANSWER;
        escalateToGeneral(refusal, OFF_TOPIC);

        JsonNode second = chat(OFF_TOPIC);

        assertTrue(second.get("answer").asText().startsWith("I don't have that"),
                () -> "the corpus still cannot answer this: " + second.get("answer").asText());
        assertFalse(second.get("answer").asText().contains("Icelandair"));
    }

    // The escape hatch is a measurement, which is the whole reason it is a button and not a
    // sentence telling students to go and ask a chatbot. It lands on the instructor's page as a
    // count and as a mark on the question it came from.
    @Test
    void escalatingIsCountedOnTheInstructorsPage() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);

        JsonNode before = confusion();
        assertEquals(0, before.get("totals").get("escalatedToGeneral").asInt());
        assertFalse(before.get("ungrounded").get(0).get("escalatedToGeneral").asBoolean());

        escalateToGeneral(refusal, OFF_TOPIC);

        JsonNode after = confusion();
        assertEquals(1, after.get("totals").get("escalatedToGeneral").asInt());
        assertTrue(after.get("ungrounded").get(0).get("escalatedToGeneral").asBoolean());
        assertEquals(1, after.get("totals").get("questionsAsked").asInt(),
                "a click is not a question: escalating must not inflate the denominator");
    }

    // The transcript keeps it, marked. A later turn in the same conversation replays it as history,
    // and the marker is what stops the model from treating its own ungrounded paragraph as one more
    // source it may cite.
    @Test
    void theAnswerIsStoredAsItsOwnKindOfTurn() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);
        chatClient.nextAnswer = GENERAL_ANSWER;

        escalateToGeneral(refusal, OFF_TOPIC);

        // The turns are written through JPA and read back here with JdbcTemplate, which does not
        // go through the EntityManager and so does not trigger Hibernate's flush. In production the
        // request's transaction commits and the question is moot; inside this one it has to be
        // asked for.
        entityManager.flush();
        String conversationId = refusal.get("conversationId").asText();
        Integer general = jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where conversation_id = cast(? as uuid) and role = 'GENERAL'",
                Integer.class, conversationId);
        assertEquals(1, general);
        Integer questions = jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where conversation_id = cast(? as uuid) and role = 'USER'",
                Integer.class, conversationId);
        assertEquals(1, questions, "the question was already in the transcript; it must not be doubled");
    }

    // Somebody else's conversation is somebody else's. The id is the only thing the client sends
    // that names a row, so it is scoped to the course and the caller, like every other id here.
    @Test
    void anotherStudentsConversationIsNotReachable() throws Exception {
        JsonNode refusal = chat(OFF_TOPIC);
        User other = saveUser("other");
        addMember(other, "MEMBER");
        String otherToken = jwtService.generateAccessToken(other);

        mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat/general")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GeneralBody(
                                OFF_TOPIC,
                                refusal.get("conversationId").asText(),
                                refusal.get("questionEventId").asText()))))
                .andExpect(status().isNotFound());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode escalateToGeneral(JsonNode refusal, String question) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat/general")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GeneralBody(
                                question,
                                refusal.get("conversationId").asText(),
                                refusal.get("questionEventId").asText()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode chat(String question) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode confusion() throws Exception {
        String response = mockMvc.perform(get("/api/v1/courses/" + courseId + "/analytics/confusion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("General " + prefix);
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

    private record GeneralBody(String question, String conversationId, String questionEventId) { }
}
