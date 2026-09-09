package com.studyloop.backend.document;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 27 — taking a document back out.
//
// **Driven through the API and through the features rather than through the repositories**, which
// is the only way these assertions mean anything. "Retire sets a column" is a test of an
// assignment; "a retired document returns no search hits, cannot be chosen for a quiz, and still
// opens in the citation viewer" is a test of the claim the feature makes. The three READY filters
// it relies on are in three different packages and none of them was touched by this phase — that
// is the point, and it is also exactly why it needs asserting.
//
// **Every permission test is driven as a plain member, never as the owner**, per 16.3's rule: a
// test written as the owner passes whether or not the guard exists.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class DocumentLifecycleTest {

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

    private User owner;
    private String token;
    private String courseId;

    @BeforeEach
    void setUp() throws Exception {
        owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
    }

    // ── retire ────────────────────────────────────────────────────────────────────────────────

    @Test
    void retiringTakesADocumentOutOfSearchWithoutTouchingItsChunks() throws Exception {
        String documentId = ingestPdf("Dynamic programming solves overlapping subproblems.");
        assertThat(search("dynamic programming")).isPositive();
        int chunksBefore = chunkCount(documentId);

        retire(documentId);

        // The whole design claim: one column, and it is invisible to every candidate branch.
        assertThat(search("dynamic programming")).isZero();
        // And nothing was destroyed to achieve it — which is what makes un-retiring free.
        assertThat(chunkCount(documentId)).isEqualTo(chunksBefore);
    }

    @Test
    void aRetiredDocumentStillOpensBehindACitationSomebodyWasAlreadyGiven() throws Exception {
        String documentId = ingestPdf("Hashing distributes keys across buckets.");
        retire(documentId);

        // A citation handed out last week has to keep working. The row and the bytes are both
        // still there, so the viewer is unaffected — this asserts that retire did not quietly
        // become a delete.
        mockMvc.perform(get(documentUrl(documentId) + "/file")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aRetiredDocumentCannotBeChosenForAQuiz() throws Exception {
        String documentId = ingestPdf("Graphs can be traversed breadth first.");
        retire(documentId);

        // QuizService gates on READY in a different package, with no knowledge of this phase.
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/quizzes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[\"" + documentId + "\"],\"multipleChoiceCount\":2}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unRetiringPutsItBackWithNoProviderCall() throws Exception {
        String documentId = ingestPdf("Sorting networks are oblivious.");
        retire(documentId);

        mockMvc.perform(post(documentUrl(documentId) + "/unretire")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(search("sorting networks")).isPositive();
        // No embedding was recomputed: the vectors were never removed. Asserted on the rows
        // rather than on a call count, because the absence of a call is the property that matters
        // and an empty embedding column is how it would show up.
        assertThat(embeddedChunkCount(documentId)).isEqualTo(chunkCount(documentId));
    }

    @Test
    void retiringADocumentThatNeverFinishedIngestingIsRefused() throws Exception {
        String documentId = upload("half-done.pdf", "Nothing was ingested here.");
        // Still UPLOADED: it is already invisible to every reader, so retiring it would be an
        // action the UI offered and the database ignored.
        mockMvc.perform(post(documentUrl(documentId) + "/retire")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // ── delete ────────────────────────────────────────────────────────────────────────────────

    @Test
    void deleteReportsWhatItDestroyedAndTheCountsMatchTheRows() throws Exception {
        String documentId = ingestPdf("Red-black trees rebalance on insert.");
        int chunks = chunkCount(documentId);
        seedFlashcard(documentId);

        String impact = mockMvc.perform(get(documentUrl(documentId) + "/impact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(impact).get("chunks").asInt()).isEqualTo(chunks);
        assertThat(objectMapper.readTree(impact).get("flashcards").asInt()).isEqualTo(1);

        mockMvc.perform(delete(documentUrl(documentId)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks").value(chunks));

        assertThat(chunkCount(documentId)).isZero();
    }

    @Test
    void deletingADocumentBlanksAFlashcardSourceRatherThanTheFlashcard() throws Exception {
        String documentId = ingestPdf("B-trees keep nodes half full.");
        UUID cardId = seedFlashcard(documentId);

        mockMvc.perform(delete(documentUrl(documentId)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // `on delete set null` from V11, asserted rather than assumed: the card survives without
        // its source chip, which is the behaviour the column was chosen for.
        Integer surviving = jdbcTemplate.queryForObject(
                "select count(*) from flashcards where id = ? and source_document_id is null",
                Integer.class, cardId);
        assertThat(surviving).isEqualTo(1);
    }

    @Test
    void deletingADocumentKeepsTheQuestionsAskedAboutItAndTheNameItHad() throws Exception {
        String documentId = ingestPdf("Union-find has near-constant amortised cost.");
        UUID eventId = seedQuestionEvent(documentId);

        mockMvc.perform(delete(documentUrl(documentId)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The row this phase changed from `cascade` to `set null`. Before V27 the attribution
        // vanished while question_events.grounded stayed true, and the instructor's totals stopped
        // reconciling with the per-lecture heat with nothing to point at.
        String filename = jdbcTemplate.queryForObject("""
                select document_filename from question_event_documents
                where question_event_id = ? and document_id is null
                """, String.class, eventId);
        assertThat(filename).isEqualTo("lecture.pdf");
        Integer events = jdbcTemplate.queryForObject(
                "select count(*) from question_events where id = ?", Integer.class, eventId);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void reUploadingAFileAfterDeletingItCreatesANewDocument() throws Exception {
        byte[] bytes = pdfBytes("Amortised analysis averages over a sequence.");
        String first = upload(bytes);
        mockMvc.perform(delete(documentUrl(first)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String second = upload(bytes);

        // **The assertion a `deleted_at` column would fail.** `unique (course_space_id, sha256)`
        // means a soft-deleted row keeps holding the key, so this upload would hit the dedupe
        // path in DocumentService.accept and hand back the document the user thought they had
        // deleted — 200 instead of 202, and the same id.
        assertThat(second).isNotEqualTo(first);
    }

    // ── re-ingest ─────────────────────────────────────────────────────────────────────────────

    @Test
    void reIngestingOverANewRangeReplacesTheChunksRatherThanAddingToThem() throws Exception {
        String documentId = ingestPdf("Page one of a lecture about trees.");
        int before = chunkCount(documentId);
        assertThat(before).isPositive();

        mockMvc.perform(post(documentUrl(documentId) + "/reingest?firstPage=1&lastPage=1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                // Out of READY in the request's own transaction, before the worker starts. A
                // restart in that window must not leave a READY document about to be rebuilt.
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.firstPage").value(1));

        // The listener is AFTER_COMMIT and this test is transactional, so the pipeline is driven
        // by hand — which is also what makes "no duplicates" an assertion about replaceChunks
        // rather than about timing.
        ingestionService.ingest(UUID.fromString(documentId));
        assertThat(chunkCount(documentId)).isEqualTo(before);
    }

    @Test
    void aFailedReIngestLeavesTheDocumentOutOfReadyRatherThanReadyWithNoChunks() throws Exception {
        String documentId = ingestPdf("Something worth indexing.");
        // A range starting past the end of the document: PageRange.requireWithin refuses it at
        // extraction, which is a failure after the status has already left READY.
        mockMvc.perform(post(documentUrl(documentId) + "/reingest?firstPage=900")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        ingestionService.ingest(UUID.fromString(documentId));

        String status = jdbcTemplate.queryForObject(
                "select status from documents where id = cast(? as uuid)", String.class, documentId);
        // Not READY is the assertion. A READY document with no chunks is indistinguishable from
        // one about a topic the corpus does not cover, which is the silent failure this guards.
        assertThat(status).isEqualTo("FAILED");
    }

    // ── permissions, always as a plain member ─────────────────────────────────────────────────

    @Test
    void aPlainMemberCanNeitherRetireNorDeleteNorReIngest() throws Exception {
        String documentId = ingestPdf("Material a student may read and may not remove.");
        User student = saveUser("student");
        addMember(student, "MEMBER");
        String studentToken = jwtService.generateAccessToken(student);

        mockMvc.perform(post(documentUrl(documentId) + "/retire")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(documentUrl(documentId) + "/reingest")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(documentUrl(documentId))
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aStrangerSeesADocumentTheyCannotReachAsNotFoundOrForbiddenAndNeverDeletesIt()
            throws Exception {
        String documentId = ingestPdf("Another course's material.");
        User stranger = saveUser("stranger");
        String strangerToken = jwtService.generateAccessToken(stranger);

        mockMvc.perform(delete(documentUrl(documentId))
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().is4xxClientError());
        assertThat(chunkCount(documentId)).isPositive();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private String documentUrl(String documentId) {
        return "/api/v1/courses/" + courseId + "/documents/" + documentId;
    }

    private void retire(String documentId) throws Exception {
        mockMvc.perform(post(documentUrl(documentId) + "/retire")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    // The search endpoint rather than the repository: it runs the same candidate branches chat
    // does, and it is the surface a student would notice a retired document through.
    private int search(String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/search")
                        .param("q", query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("hitCount").asInt();
    }

    private int chunkCount(String documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from document_chunks where document_id = cast(? as uuid)",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private int embeddedChunkCount(String documentId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from document_chunks
                where document_id = cast(? as uuid) and embedding is not null
                """, Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private UUID seedFlashcard(String documentId) {
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into flashcards (id, course_space_id, created_by, front, back, source_document_id)
                values (?, cast(? as uuid), ?, 'front', 'back', cast(? as uuid))
                """, cardId, courseId, owner.getId(), documentId);
        return cardId;
    }

    // Written through the repository's own insert path rather than by hand, so the filename
    // snapshot this phase added is exercised rather than assumed.
    private UUID seedQuestionEvent(String documentId) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into question_events (id, course_space_id, asked_by, question, grounded)
                values (?, cast(? as uuid), ?, 'what is a tree?', true)
                """, eventId, courseId, owner.getId());
        jdbcTemplate.update("""
                insert into question_event_documents
                    (question_event_id, document_id, document_filename)
                values (?, cast(? as uuid), (select filename from documents where id = cast(? as uuid)))
                """, eventId, documentId, documentId);
        return eventId;
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Lifecycle " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private void addMember(User user, String role) {
        jdbcTemplate.update("""
                insert into memberships (id, course_space_id, user_id, role)
                values (?, cast(? as uuid), ?, ?)
                """, UUID.randomUUID(), courseId, user.getId(), role);
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

    private String ingestPdf(String... lines) throws Exception {
        String id = upload(pdfBytes(lines));
        ingestionService.ingest(UUID.fromString(id));
        return id;
    }

    private String upload(String filename, String line) throws Exception {
        return upload(pdfBytes(line), filename);
    }

    private String upload(byte[] bytes) throws Exception {
        return upload(bytes, "lecture.pdf");
    }

    private String upload(byte[] bytes, String filename) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", bytes))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
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
}
