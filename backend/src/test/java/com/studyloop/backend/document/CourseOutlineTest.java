package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 28.4 — what is in this course.
//
// Three joins over columns that already existed. The interesting assertions are the two the plan
// names: that a document nobody has asked about is *reported* as such rather than merely absent,
// and that a member's private note does not appear in another member's outline — this endpoint
// reads `document_chunks` directly, which is exactly where 16.3's visibility rule has to be
// repeated rather than inherited.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class CourseOutlineTest {

    private static final String TREAP_QUESTION = "What is a treap?";

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
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        treapsId = ingestPdf("treaps.pdf",
                "A treap is a binary search tree whose nodes also carry a random heap priority.");
        hashingId = ingestPdf("hashing.pdf",
                "Linear probing resolves a hash collision by scanning forward to the next free slot.");
    }

    @Test
    void everyReadyDocumentIsListedWithItsCounts() throws Exception {
        JsonNode outline = outline(token);

        assertEquals(2, outline.get("documents").size());
        JsonNode treaps = documentNamed(outline, "treaps.pdf");
        assertTrue(treaps.get("chunkCount").asInt() > 0, "a ready document has passages");
        assertTrue(treaps.get("pageCount").asInt() > 0);
    }

    // **The half this page exists for.** A question lands on one lecture and the other is reported
    // as untouched — which is the claim a student a week from an exam actually wants, and the one
    // the instructor's confusion page cannot make because it reports what *was* asked.
    //
    // The attribution is written directly rather than driven through a chat turn, and that is a
    // correction rather than a shortcut: the first version of this test asked a question about
    // treaps and expected only treaps.pdf to be attributed. It failed, and it was right to. The
    // suite's stub embedder produces a vector per string, so the dense branch's nearest neighbours
    // are noise — the fused six came from both documents and both were recorded. That says
    // something true about the *stub*, nothing about this query, and a test that only passed
    // because retrieval happened to pick one document would be measuring the wrong thing.
    @Test
    void aDocumentNobodyHasAskedAboutIsReportedAsSuch() throws Exception {
        assertEquals(2, outline(token).get("neverAsked").asInt(),
                "precondition: nothing has been asked yet");

        recordQuestionAgainst(treapsId, "What is a treap?");

        JsonNode outline = outline(token);
        assertEquals(1, outline.get("neverAsked").asInt(),
                "one lecture has now answered a question and the other has not");
        assertEquals(1, documentNamed(outline, "treaps.pdf").get("questionCount").asInt());
        assertEquals(0, documentNamed(outline, "hashing.pdf").get("questionCount").asInt());
    }

    // The count is all-time rather than windowed, which is what makes "never asked" a claim rather
    // than a report about a quiet fortnight. Asserted by backdating the event past every window the
    // confusion page offers and checking the outline still counts it.
    @Test
    void theQuestionCountIsAllTimeRatherThanWindowed() throws Exception {
        recordQuestionAgainst(treapsId, "What is a treap?");
        jdbcTemplate.update("update question_events set created_at = now() - interval '400 days' "
                            + "where course_space_id = cast(? as uuid)", courseId);

        assertEquals(1, documentNamed(outline(token), "treaps.pdf").get("questionCount").asInt(),
                "a question from a year ago still means somebody has been here");
        assertEquals(1, outline(token).get("neverAsked").asInt());
    }

    // A chat turn still has to land somewhere: this is the end-to-end half of the pair above,
    // asserting that asking a question moves the outline at all without claiming to know which
    // document the stub's retrieval will attribute it to.
    @Test
    void askingAQuestionMovesTheOutline() throws Exception {
        chat(TREAP_QUESTION);

        JsonNode outline = outline(token);
        assertTrue(outline.get("neverAsked").asInt() < 2,
                "a question grounded on this course has to show up against something");
    }

    // 16.3's visibility rule, repeated here because this endpoint reads `document_chunks` directly
    // rather than going through retrieval. A course outline that listed somebody's photographed
    // notebook would be the same leak by a quieter route.
    @Test
    void aPrivateNoteIsNotInAnotherMembersOutline() throws Exception {
        jdbcTemplate.update("update documents set visibility = 'PRIVATE' where id = cast(? as uuid)",
                treapsId);

        User classmate = saveUser("classmate");
        addMember(classmate);
        JsonNode theirs = outline(jwtService.generateAccessToken(classmate));

        assertEquals(1, theirs.get("documents").size(),
                "a private document belongs to the member who uploaded it");
        assertEquals("hashing.pdf", theirs.get("documents").get(0).get("filename").asText());

        assertEquals(2, outline(token).get("documents").size(),
                "and is still in the uploader's own outline");
    }

    // A retired document is out of every answer by design (27.3), so listing it in "what's in this
    // course" would be describing material the assistant will never use.
    @Test
    void aRetiredDocumentIsNotInTheOutline() throws Exception {
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/documents/" + hashingId + "/retire")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode outline = outline(token);

        assertEquals(1, outline.get("documents").size());
        assertEquals("treaps.pdf", outline.get("documents").get(0).get("filename").asText());
    }

    // Sections come from `section_path`, which 13.4 writes at ingest and which only SectionExpander
    // had ever read. A page span of null-null is a real state — text extracted without page
    // boundaries — so the assertion is on the structure rather than on a particular number.
    @Test
    void sectionsCarryTheirPageSpanAndPassageCount() throws Exception {
        JsonNode sections = documentNamed(outline(token), "treaps.pdf").get("sections");

        for (JsonNode section : sections) {
            assertFalse(section.get("sectionPath").asText().isBlank());
            assertTrue(section.get("chunkCount").asInt() > 0);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode documentNamed(JsonNode outline, String filename) {
        for (JsonNode document : outline.get("documents")) {
            if (filename.equals(document.get("filename").asText())) {
                return document;
            }
        }
        throw new AssertionError("no document named " + filename + " in the outline");
    }

    // One grounded question, attributed to one document — the two rows the chat path writes at the
    // end of a turn, written here so the attribution is the test's input rather than the stub's
    // opinion.
    private void recordQuestionAgainst(String documentId, String question) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into question_events (id, course_space_id, asked_by, question, grounded)
                values (?, cast(? as uuid), (select owner_id from course_spaces where id = cast(? as uuid)),
                        ?, true)
                """, eventId, courseId, courseId, question);
        jdbcTemplate.update("""
                insert into question_event_documents (question_event_id, document_id)
                values (?, cast(? as uuid))
                """, eventId, documentId);
    }

    private JsonNode outline(String asToken) throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/outline")
                        .header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void chat(String question) throws Exception {
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody(question, null, List.of()))))
                .andExpect(status().isOk());
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Outline " + prefix);
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
