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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 28.2 — a question aimed at one lecture.
//
// **The assertion this class exists for is the refusal.** Narrowing the corpus means fewer
// candidates, all of them from the chosen place, so the average relevance of what comes back goes
// *up* — and a confidence gate that read relevance relative to the candidate pool would start
// answering questions the chosen lecture cannot answer, confidently and with citations from the
// wrong chapter. The gate's threshold is a cross-encoder score and corpus-independent by
// construction (12.2), so it should hold. "Should hold" is what a test is for.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class ScopedRetrievalTest {

    // Each question shares its distinctive vocabulary with exactly one of the two documents, so
    // the lexical half can find it in one and nothing at all in the other. That is what makes the
    // scope the variable under test rather than the wording.
    private static final String TREAP_QUESTION = "What is a treap?";
    private static final String HASHING_QUESTION = "What is linear probing?";

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
    private String treapsId;
    private String hashingId;

    @BeforeEach
    void ingestTwoLectures() throws Exception {
        User owner = saveUser();
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        treapsId = ingestPdf("treaps.pdf",
                "A treap is a binary search tree whose nodes also carry a random heap priority.",
                "Rotations restore the heap order after an insertion into the treap.");
        hashingId = ingestPdf("hashing.pdf",
                "Linear probing resolves a hash collision by scanning forward to the next free slot.",
                "Its performance degrades as the load factor of the table approaches one.");
    }

    // The whole of 28.2's risk, in one assertion: a question the chosen lecture cannot answer must
    // still refuse, even though the other lecture in the same course answers it perfectly.
    @Test
    void aQuestionTheChosenLectureCannotAnswerStillRefuses() throws Exception {
        String scoped = ask(HASHING_QUESTION, List.of(treapsId));

        assertTrue(scoped.startsWith("I don't have that"),
                () -> "scoping to the wrong lecture must refuse, not answer from the other one: " + scoped);
    }

    // The other half of that pair, and it has to be here: without it the test above would pass on
    // an implementation that refuses everything.
    @Test
    void theSameQuestionScopedToTheRightLectureIsAnswered() throws Exception {
        JsonNode answer = chat(HASHING_QUESTION, List.of(hashingId));

        assertTrue(answer.get("citations").size() > 0,
                "the lecture that covers it must still answer when it is the one chosen");
        assertEquals(hashingId, answer.get("citations").get(0).get("documentId").asText());
    }

    // Every citation, not just the first: one stray candidate from outside the scope is exactly
    // the failure the predicate exists to prevent, and it would most likely arrive at rank 4.
    @Test
    void everyCitationComesFromTheChosenDocument() throws Exception {
        JsonNode citations = chat(TREAP_QUESTION, List.of(treapsId)).get("citations");

        assertTrue(citations.size() > 0, "precondition: the answer cited something");
        for (JsonNode citation : citations) {
            assertEquals(treapsId, citation.get("documentId").asText(),
                    "a scoped answer must not cite a document the reader excluded");
        }
    }

    // An empty scope is the whole course, which is what every caller sent before this field
    // existed — so the unscoped path has to be byte-for-byte what it was.
    @Test
    void anEmptyScopeSearchesTheWholeCourse() throws Exception {
        JsonNode both = chat(HASHING_QUESTION, List.of());

        assertTrue(both.get("citations").size() > 0);
        assertEquals(hashingId, both.get("citations").get(0).get("documentId").asText(),
                "an unscoped question must still find the lecture that answers it");
    }

    // **A scoped turn neither reads nor writes the semantic cache.** `chat_cache_entries` is keyed
    // on the course and the question's embedding with no room for where the reader was looking, so
    // storing a scoped answer would hand a chapter-6 answer to the next person who asks the whole
    // course. The row count is the assertion because nothing else would show it: the answer is
    // correct either way.
    @Test
    void aScopedAnswerIsNeverCached() throws Exception {
        chat(HASHING_QUESTION, List.of(hashingId));

        assertEquals(0, cachedEntries(), "a scoped answer must not enter a cache keyed course-wide");

        chat(HASHING_QUESTION, List.of());

        assertEquals(1, cachedEntries(), "the same question over the whole course is cacheable");
    }

    // A scope naming a document from another course narrows to nothing rather than widening to
    // something: every branch still carries `course_space_id = ?`, so the scope can only ever be an
    // intersection. Asserted with a random id, which is the same case as a stale one.
    @Test
    void anUnknownDocumentIdNarrowsToNothing() throws Exception {
        String answer = ask(HASHING_QUESTION, List.of(UUID.randomUUID().toString()));

        assertTrue(answer.startsWith("I don't have that"),
                () -> "an id outside the course must not widen the search: " + answer);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String ask(String question, List<String> documentIds) throws Exception {
        return chat(question, documentIds).get("answer").asText();
    }

    private JsonNode chat(String question, List<String> documentIds) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody(question, null, documentIds))))
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
        user.setEmail("scoped-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Scope Tester");
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

    private String ingestPdf(String filename, String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String documentId = objectMapper.readTree(body).get("id").asText();
        ingestionService.ingest(UUID.fromString(documentId));
        return documentId;
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
