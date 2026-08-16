package com.studyloop.backend.forum;

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The escalation loop (Phase 9.2), tested as the loop rather than as five endpoints: chat refuses
// a question → the refusal becomes a thread → a classmate answers → an instructor accepts → the
// same question is answerable. The fourth test is the one that would notice if any link in that
// chain quietly stopped connecting, and it asserts the end state through the chat API, not
// through the tables underneath it.
//
// The off-topic question is genuinely off-topic against the ingested lecture, so the refusal is
// the confidence gate doing its job rather than a fixture pretending. After the answer is
// accepted, the same question retrieves the forum text lexically — which is exactly why the
// question's own words are written into the corpus alongside the answer.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class ForumEscalationTest {

    private static final String OFF_TOPIC = "Which airline flies to Reykjavik?";
    private static final String ANSWER = "Icelandair and Play both fly there from most of Europe.";

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
    private String documentId;

    @BeforeEach
    void createCourseWithMaterialAndAStudent() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        documentId = ingestPdf("Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");

        User student = saveUser("student");
        addMember(student, "MEMBER");
        studentToken = jwtService.generateAccessToken(student);

        chatClient.reset();
    }

    // A refusal is a dead end unless the client is handed something to escalate. The id it gets
    // back is the analytics row, so the thread attaches to the question that was actually asked
    // rather than to a second copy of its text.
    @Test
    void aRefusedQuestionCarriesTheIdThatOpensAThread() throws Exception {
        JsonNode refusal = chat(studentToken, OFF_TOPIC);

        assertTrue(refusal.get("answer").asText().startsWith("I don't have that"),
                "precondition: the gate refused");
        String questionEventId = refusal.get("questionEventId").asText(null);
        assertNotNull(questionEventId, "a refusal must come back with the question it refused");

        JsonNode thread = escalate(studentToken, OFF_TOPIC, questionEventId);
        assertEquals(OFF_TOPIC, thread.get("title").asText());
        assertEquals("OPEN", thread.get("status").asText());
        assertEquals(0, thread.get("answers").size());
        assertFalse(thread.get("canAccept").asBoolean(), "a plain member may ask but not accept");
    }

    // An answered question comes back with no event id at all — there is nothing to escalate, and
    // offering the button would be noise.
    @Test
    void anAnsweredQuestionHasNothingToEscalate() throws Exception {
        JsonNode answered = chat(studentToken, "What is dynamic programming?");

        assertFalse(answered.get("answer").asText().startsWith("I don't have that"),
                "precondition: this one was answerable");
        assertTrue(answered.get("questionEventId").isNull());
    }

    // Two students hitting "ask the class" on the same refusal is one discussion. A unique index
    // enforces it; this checks the service resolves to the existing thread instead of failing.
    @Test
    void escalatingTheSameRefusalTwiceReusesTheOneThread() throws Exception {
        String questionEventId = refuseAndGetEventId();

        String first = escalate(studentToken, OFF_TOPIC, questionEventId).get("id").asText();
        String second = escalate(token, OFF_TOPIC, questionEventId).get("id").asText();

        assertEquals(first, second, "the same refusal must not split into two threads");
        assertEquals(1, listThreads(token, null).size());
    }

    // The permission asymmetry, which is the whole security story of this feature: anyone may
    // answer, because a classmate who knows is the fastest route to an answer — but accepting
    // writes member-authored text into the corpus every future answer may be grounded on, so it
    // sits behind the same guard as uploading a document.
    @Test
    void anyMemberCanAnswerButOnlyAManagerCanAccept() throws Exception {
        String threadId = escalate(studentToken, OFF_TOPIC, refuseAndGetEventId()).get("id").asText();

        JsonNode withAnswer = answer(studentToken, threadId, ANSWER);
        assertEquals(1, withAnswer.get("answers").size(), "a plain member may answer");
        String answerId = withAnswer.get("answers").get(0).get("id").asText();

        mockMvc.perform(post(threadPath(threadId) + "/answers/" + answerId + "/accept")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        JsonNode accepted = objectMapper.readTree(
                mockMvc.perform(post(threadPath(threadId) + "/answers/" + answerId + "/accept")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertEquals("ANSWERED", accepted.get("status").asText());
        assertEquals(answerId, accepted.get("acceptedAnswerId").asText());
        assertTrue(accepted.get("answers").get(0).get("accepted").asBoolean());
        assertTrue(accepted.get("inCorpus").asBoolean(),
                "accepting is what puts the answer into the materials");
    }

    // The loop, end to end and asserted through chat: the question that was refused is answerable
    // once the class has answered it, and the answer is cited as what it is.
    @Test
    void anAcceptedAnswerMakesTheRefusedQuestionAnswerable() throws Exception {
        acceptAnAnswerFor(OFF_TOPIC);

        JsonNode second = chat(studentToken, OFF_TOPIC);

        assertFalse(second.get("answer").asText().startsWith("I don't have that"),
                "the corpus now contains the answer, so the gate must not refuse");
        JsonNode citations = second.get("citations");
        assertTrue(citations.size() > 0, "the answer must be grounded on something");

        boolean citesTheForum = false;
        for (JsonNode citation : citations) {
            if ("FORUM".equals(citation.get("documentSource").asText())) {
                citesTheForum = true;
                assertTrue(citation.get("filename").asText().startsWith("Forum ·"),
                        "a forum source should say so in its name");
                assertTrue(citation.get("pageNumber").isNull(),
                        "there is no page to jump to — this text was never a file");
            }
        }
        assertTrue(citesTheForum, () -> "the accepted answer should be among the sources: " + citations);
    }

    // A forum-derived document is corpus, not material. It has no file, and it does not belong in
    // the list of things somebody uploaded — the loop must not quietly add rows to that table.
    @Test
    void theAcceptedAnswerIsCorpusButNotAnUploadedFile() throws Exception {
        acceptAnAnswerFor(OFF_TOPIC);

        JsonNode documents = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertEquals(1, documents.size(), "only the uploaded lecture belongs in the materials list");
        assertEquals(documentId, documents.get(0).get("id").asText());

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + forumDocumentId() + "/file")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // The instructor's side of the loop: a gap on the confusion page stops being a complaint once
    // it links to the discussion it became.
    @Test
    void theGapListLinksToTheThreadItBecame() throws Exception {
        String questionEventId = refuseAndGetEventId();

        JsonNode before = confusionGaps();
        assertEquals(1, before.size());
        assertTrue(before.get(0).get("threadId").isNull(), "nobody has escalated it yet");
        assertEquals(questionEventId, before.get(0).get("questionEventId").asText());

        String threadId = escalate(studentToken, OFF_TOPIC, questionEventId).get("id").asText();

        assertEquals(threadId, confusionGaps().get(0).get("threadId").asText());
    }

    // Course membership guards the forum as it guards everything else in a course.
    @Test
    void someoneOutsideTheCourseSeesNoneOfIt() throws Exception {
        String outsider = jwtService.generateAccessToken(saveUser("outsider"));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/forum/threads")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    // Refuse a question, escalate it, answer it, accept the answer — the setup every "and then"
    // test needs.
    private void acceptAnAnswerFor(String question) throws Exception {
        String threadId = escalate(studentToken, question, refuseAndGetEventId()).get("id").asText();
        String answerId = answer(studentToken, threadId, ANSWER).get("answers").get(0).get("id").asText();
        mockMvc.perform(post(threadPath(threadId) + "/answers/" + answerId + "/accept")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String refuseAndGetEventId() throws Exception {
        return chat(studentToken, OFF_TOPIC).get("questionEventId").asText();
    }

    private JsonNode chat(String asToken, String question) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode escalate(String asToken, String question, String questionEventId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/forum/threads")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ThreadBody(question, null, questionEventId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode answer(String asToken, String threadId, String body) throws Exception {
        String response = mockMvc.perform(post(threadPath(threadId) + "/answers")
                        .header("Authorization", "Bearer " + asToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnswerBody(body))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode listThreads(String asToken, String status) throws Exception {
        String response = mockMvc.perform(get("/api/v1/courses/" + courseId + "/forum/threads"
                                + (status == null ? "" : "?status=" + status))
                        .header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode confusionGaps() throws Exception {
        String response = mockMvc.perform(get("/api/v1/courses/" + courseId + "/analytics/confusion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("ungrounded");
    }

    private String threadPath(String threadId) {
        return "/api/v1/courses/" + courseId + "/forum/threads/" + threadId;
    }

    // The corpus document the accepted answer became. Not exposed by any endpoint — it is corpus,
    // not material — so the test reads it the only way a client couldn't.
    private String forumDocumentId() {
        List<String> ids = jdbcTemplate.queryForList(
                "select id from documents where course_space_id = cast(? as uuid) and source = 'FORUM'",
                String.class, courseId);
        assertEquals(1, ids.size(), "accepting should have written exactly one corpus document");
        return ids.get(0);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Forum " + prefix);
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

    private record ThreadBody(String title, String body, String questionEventId) { }

    private record AnswerBody(String body) { }
}
