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
import jakarta.persistence.EntityManager;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 28.1 — the read path for chat.
//
// **These tests are about the endpoints, not about the rows.** `chat_conversations` and
// `chat_messages` have been written correctly on every turn since Phase 5; what did not exist was
// anything that read them back, which is why a page reload silently started a new thread. So every
// assertion here goes through HTTP: the rows were never the thing that was broken.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class ConversationHistoryTest {

    // Shares vocabulary with the ingested PDF so the confidence gate passes on the lexical hit —
    // the stub's embeddings are random per string, so the semantic half is noise here. Same trick
    // SemanticCacheTest uses, and for the same reason: these tests are not about retrieval.
    private static final String QUESTION = "What is a treap?";
    private static final String FOLLOW_UP = "What is its expected height?";

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

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;

    @BeforeEach
    void createCourseWithMaterial() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        ingestPdf("A treap is a binary search tree whose nodes also carry a random heap priority.",
                "Its expected height is logarithmic in the number of nodes.");
    }

    // The bug this whole sub-phase exists for, stated as an assertion: a second turn continues the
    // thread instead of opening one, and — the part that was invisible before — the list proves it.
    @Test
    void aSecondTurnContinuesTheThreadRatherThanOpeningOne() throws Exception {
        String conversationId = chat(QUESTION, null).get("conversationId").asText();
        chat(FOLLOW_UP, conversationId);

        JsonNode conversations = list(token);

        assertEquals(1, conversations.size(), "two turns in one thread must be one conversation");
        assertEquals(conversationId, conversations.get(0).get("id").asText());
        assertEquals(4, conversations.get(0).get("messageCount").asLong(),
                "two questions and two answers");
    }

    // The title is the first question, truncated — not a generated one. Asserted because the
    // alternative is a model call on the first turn of every conversation forever, and a test that
    // only checked "a title exists" would pass just as happily on that.
    @Test
    void theTitleIsTheFirstQuestion() throws Exception {
        chat(QUESTION, null);

        assertEquals(QUESTION, list(token).get(0).get("title").asText());
    }

    // **The assertion 28.1 is worth the most for.** A resumed thread whose [n] markers point at
    // nothing is worse than one with no markers at all, and the citations column is what stops
    // that — so this checks the stored source is a real, openable one rather than merely present.
    @Test
    void aResumedTranscriptCarriesItsCitations() throws Exception {
        JsonNode answer = chat(QUESTION, null);
        String conversationId = answer.get("conversationId").asText();
        assertTrue(answer.get("citations").size() > 0, "precondition: the answer cited something");

        JsonNode transcript = transcript(token, conversationId);
        JsonNode assistant = transcript.get("messages").get(1);

        assertEquals("ASSISTANT", assistant.get("role").asText());
        assertEquals(answer.get("citations").size(), assistant.get("citations").size(),
                "the stored turn must carry the sources it was shown with");
        JsonNode stored = assistant.get("citations").get(0);
        assertFalse(stored.get("chunkId").isNull(), "a stored citation has to be clickable");
        assertFalse(stored.get("documentId").isNull());
        assertEquals(answer.get("citations").get(0).get("filename").asText(),
                stored.get("filename").asText());
    }

    // A USER turn has no sources, and the empty list is stored rather than left null — "this turn
    // had no sources" is a fact the client renders, not an absence of data.
    @Test
    void aQuestionTurnCarriesNoCitations() throws Exception {
        String conversationId = chat(QUESTION, null).get("conversationId").asText();

        JsonNode user = transcript(token, conversationId).get("messages").get(0);

        assertEquals("USER", user.get("role").asText());
        assertEquals(0, user.get("citations").size());
    }

    // Driven as a classmate, which is the only way this can be tested honestly: a query with the
    // right `where` clause and a query with none look identical from the owner's seat.
    @Test
    void theListIsTheCallersOwnThreadsAndNobodyElses() throws Exception {
        String conversationId = chat(QUESTION, null).get("conversationId").asText();

        User classmate = saveUser("classmate");
        addMember(classmate);
        String classmateToken = jwtService.generateAccessToken(classmate);

        assertEquals(0, list(classmateToken).size(),
                "a classmate in the same course must not see somebody else's threads");

        // 404 rather than 403, and deliberately: the two answers are distinguishable, and a 403
        // would confirm that this conversation exists.
        mockMvc.perform(get(conversationPath(conversationId))
                        .header("Authorization", "Bearer " + classmateToken))
                .andExpect(status().isNotFound());
    }

    // Phase 27.4's last open bullet. The author only — a manager who can retire a document has no
    // business in a member's private thread.
    @Test
    void theAuthorCanDeleteAThreadAndItsTurnsGoWithIt() throws Exception {
        String conversationId = chat(QUESTION, null).get("conversationId").asText();
        assertEquals(2, storedMessages(conversationId), "precondition: the turns are there");

        // **Detached before the delete, and this is a fact about the test harness rather than about
        // the code.** In production each request is its own transaction, so by the time the delete
        // runs nothing holds those `ChatMessage` instances. Here the whole test is one transaction
        // and the chat POST left them managed — removing the conversation they point at then makes
        // the next flush throw `TransientPropertyValueException` on rows the database is perfectly
        // happy to cascade. The `flush` above has already written them, so clearing loses nothing.
        entityManager.clear();

        mockMvc.perform(delete(conversationPath(conversationId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertEquals(0, list(token).size());
        assertEquals(0, storedMessages(conversationId),
                "the messages go with it through the foreign key's cascade");
    }

    @Test
    void aClassmateCannotDeleteSomebodyElsesThread() throws Exception {
        String conversationId = chat(QUESTION, null).get("conversationId").asText();
        User classmate = saveUser("classmate");
        addMember(classmate);

        mockMvc.perform(delete(conversationPath(conversationId))
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(classmate)))
                .andExpect(status().isNotFound());

        assertEquals(1, list(token).size(), "the owner's thread must still be there");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String conversationPath(String conversationId) {
        return "/api/v1/courses/" + courseId + "/chat/conversations/" + conversationId;
    }

    private JsonNode list(String asToken) throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/chat/conversations")
                        .header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode transcript(String asToken, String conversationId) throws Exception {
        String body = mockMvc.perform(get(conversationPath(conversationId))
                        .header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // **Flushed before reading, and that is not a formality.** The whole test runs in one
    // transaction, so the inserts JPA has queued are invisible to a JdbcTemplate query until
    // Hibernate synchronises — the count came back 0 on rows that were plainly there. It is the
    // same disagreement between a view and the thing that BUGS.md records for Phase 29.2, arriving
    // from the other side: there the ORM overwrote a native write, here it had not yet made one
    // visible.
    private int storedMessages(String conversationId) {
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where conversation_id = cast(? as uuid)",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private JsonNode chat(String question, String conversationId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody(question, conversationId, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("History " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private void addMember(User user) {
        jdbcTemplate.update(
                "insert into memberships (id, course_space_id, user_id, role) values (?, cast(? as uuid), ?, ?)",
                UUID.randomUUID(), courseId, user.getId(), "MEMBER");
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

    private record ChatBody(String question, String conversationId, java.util.List<String> documentIds) { }
}
