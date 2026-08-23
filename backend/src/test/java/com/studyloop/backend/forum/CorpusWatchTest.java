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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The corpus watch (Phase 20.1), tested as the thing it claims to be: a question that was refused,
// then a document, then the same question answered — with nothing else about the pipeline changed.
//
// Everything here turns on the confidence gate, which is why the fixtures are what they are. The
// stub embedder maps different strings to near-orthogonal vectors, so the semantic half never
// carries a test question on its own; what decides these cases is the lexical half, exactly as it
// would in a corpus that had never seen the question's wording. A thread whose words are in no
// document is refused, and it stops being refused the moment a document contains them.
//
// The annotations are copied verbatim from the other forum and chat tests, and that is deliberate:
// a distinct set here would mint a second ApplicationContext and a second Hikari pool against a
// database that allows fifteen clients in total (see BUGS.md).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class CorpusWatchTest {

    // No word of this appears in the first lecture, so the gate refuses it; every word of it
    // appears in the second, so the gate does not.
    private static final String UNANSWERED = "What is a red-black tree?";
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

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String studentToken;
    private String courseId;

    @BeforeEach
    void createCourseWithOneLectureAndAStudent() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        ingestPdf("Dynamic programming solves problems by combining subproblem solutions.");

        User student = saveUser("student");
        addMember(student, "MEMBER");
        studentToken = jwtService.generateAccessToken(student);

        chatClient.reset();
    }

    // The whole feature in one test: refused, escalated, uploaded, answered — and the answer says
    // which upload answered it.
    @Test
    void anUploadAnswersTheThreadItMadeAnswerable() throws Exception {
        String threadId = escalateARefusalOf(UNANSWERED);
        assertEquals(0, thread(threadId).get("answers").size(), "precondition: nobody has replied");

        String documentId = ingestPdf("A red-black tree is a balanced binary search tree.");

        JsonNode answers = thread(threadId).get("answers");
        assertEquals(1, answers.size(), "the upload should have answered the open thread");
        JsonNode answer = answers.get(0);
        assertEquals("ASSISTANT", answer.get("authorKind").asText());
        assertTrue(answer.get("authorName").isNull(), "no person wrote this, so no name is claimed");
        assertEquals(documentId, answer.get("sourceDocumentId").asText(),
                "the reply should name the upload that made it possible");
        assertFalse(answer.get("accepted").asBoolean());
    }

    // The thread stays open underneath it. A machine answer is a head start for whoever answers
    // properly, not a resolution — only a manager accepting a person's reply ends a thread, and
    // that rule is Phase 9.2's and is not weakened here.
    @Test
    void theThreadStaysOpenAndTheReplyCannotBecomeCourseMaterial() throws Exception {
        String threadId = escalateARefusalOf(UNANSWERED);
        ingestPdf("A red-black tree is a balanced binary search tree.");

        JsonNode thread = thread(threadId);
        assertEquals("OPEN", thread.get("status").asText(), "a machine answer does not close a thread");
        assertTrue(thread.get("acceptedAnswerId").isNull());
        assertFalse(thread.get("inCorpus").asBoolean());

        // **The rule this feature would be dangerous without.** Accepting writes text into the
        // corpus that every future answer may cite; accepting the model's own output would let a
        // course start teaching itself whatever it first guessed.
        String answerId = thread.get("answers").get(0).get("id").asText();
        mockMvc.perform(post(threadPath(threadId) + "/answers/" + answerId + "/accept")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // Silence is an answer. A thread the corpus still cannot answer must not collect a reply
    // saying so — the student has already been told that once, by the refusal that opened it.
    @Test
    void aThreadTheCorpusStillCannotAnswerIsLeftAlone() throws Exception {
        String stillOpen = escalateARefusalOf(OFF_TOPIC);

        ingestPdf("A red-black tree is a balanced binary search tree.");

        assertEquals(0, thread(stillOpen).get("answers").size(),
                "nothing uploaded has anything to do with this question");
    }

    // One machine answer per thread, ever. Without the bound, a course importing a semester of
    // lectures would answer the same open thread once per file.
    @Test
    void aSecondUploadDoesNotAnswerTheSameThreadTwice() throws Exception {
        String threadId = escalateARefusalOf(UNANSWERED);
        ingestPdf("A red-black tree is a balanced binary search tree.");
        assertEquals(1, thread(threadId).get("answers").size());

        ingestPdf("A red-black tree rotates on insert to stay balanced.");

        assertEquals(1, thread(threadId).get("answers").size(),
                "the assistant may reply to a thread once, however many documents arrive");
    }

    // The reply carries [n] markers because it came from the grounded prompt, so it has to carry
    // what they point at. A forum post has no citation model behind it, so the sources are text.
    @Test
    void theReplyListsTheSourcesItsMarkersReferTo() throws Exception {
        chatClient.nextAnswer = "A red-black tree rebalances on insert [1].";
        String threadId = escalateARefusalOf(UNANSWERED);

        ingestPdf("A red-black tree is a balanced binary search tree.");

        String body = thread(threadId).get("answers").get(0).get("body").asText();
        assertTrue(body.contains("[1]"), "precondition: the answer cites a source");
        assertTrue(body.contains("Sources:"), () -> "a marker with nothing behind it: " + body);
        assertTrue(body.contains("lecture.pdf"), () -> "the source should be named: " + body);
    }

    // **The privacy rule, and the reason retrieval has a course-scoped entry point at all.** A
    // machine answer is posted where the whole course reads it, so it may only be grounded on
    // documents the whole course can read. A member's private note is invisible to this path even
    // though it would answer the question perfectly.
    @Test
    void aPrivateDocumentCannotAnswerAThreadTheWholeCourseReads() throws Exception {
        String threadId = escalateARefusalOf(UNANSWERED);

        String privateId = ingestPdf("A red-black tree is a balanced binary search tree.",
                /* ownerOnly = */ true);

        assertEquals(0, thread(threadId).get("answers").size(),
                "a private note must not be quoted into a public thread");
        // And the same text, visible to the course, does answer it — so the test is about
        // visibility and not about the fixture failing to match.
        setVisibility(privateId, "COURSE");
        ingestionService.ingest(UUID.fromString(privateId));
        assertEquals(1, thread(threadId).get("answers").size());
    }

    // The list view says so without opening the thread: a reply is waiting, and a person should
    // look at it.
    @Test
    void theListViewFlagsThreadsTheAssistantAnswered() throws Exception {
        escalateARefusalOf(UNANSWERED);
        ingestPdf("A red-black tree is a balanced binary search tree.");

        JsonNode threads = listThreads();
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).get("assistantAnswered").asBoolean());
        assertEquals("OPEN", threads.get(0).get("status").asText());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String escalateARefusalOf(String question) throws Exception {
        JsonNode refusal = chat(question);
        assertTrue(refusal.get("answer").asText().startsWith("I don't have that"),
                () -> "precondition: the gate refused " + question);
        String questionEventId = refusal.get("questionEventId").asText();
        assertNotNull(questionEventId);

        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/forum/threads")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ThreadBody(question, null, questionEventId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
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

    private JsonNode thread(String threadId) throws Exception {
        String response = mockMvc.perform(get(threadPath(threadId))
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode listThreads() throws Exception {
        String response = mockMvc.perform(get("/api/v1/courses/" + courseId + "/forum/threads")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String threadPath(String threadId) {
        return "/api/v1/courses/" + courseId + "/forum/threads/" + threadId;
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Watch " + prefix);
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

    private String ingestPdf(String line) throws Exception {
        return ingestPdf(line, false);
    }

    // Uploading and ingesting are two steps here rather than one, exactly as they are in
    // production — the controller returns 202 and the listener ingests. Calling ingest() directly
    // keeps the sweep on this thread and inside this test's transaction.
    private String ingestPdf(String line, boolean ownerOnly) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", pdfBytes(line)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        if (ownerOnly) {
            setVisibility(id, "OWNER");
        }
        ingestionService.ingest(UUID.fromString(id));
        return id;
    }

    // Straight SQL, because visibility is set by the notes API rather than by the upload one and
    // this test is not about that path. The flush and the clear around it are not ceremony: the
    // Document is a managed entity in this test's transaction, so without them Hibernate's next
    // flush would write the whole row back from its stale in-memory copy and quietly undo this.
    private void setVisibility(String documentId, String visibility) {
        entityManager.flush();
        jdbcTemplate.update("update documents set visibility = ? where id = cast(? as uuid)",
                visibility, documentId);
        entityManager.clear();
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

    private record ThreadBody(String title, String body, String questionEventId) { }
}
