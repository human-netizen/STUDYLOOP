package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static com.studyloop.backend.document.StubAiConfig.RecordingChatClient.SUMMARY_TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 8.2 — the per-document summary and glossary. The point of the feature is that the model
// runs ONCE per document, so most of what's worth asserting is about the cache: that ingestion
// fills it, that reading it never costs a call, and that only an explicit refresh spends another.
//
// Both AI clients are stubbed via the shared StubAiConfig, so this test is deterministic, free,
// and runs in CI without any API key. Its chat client also counts calls, which is how the
// "generated once" claim is actually checked rather than assumed.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class DocumentSummaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentTermRepository termRepository;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RecordingChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The stub is a context-wide singleton, so a test that inherited another's call count or
    // canned payload would pass or fail for the wrong reason.
    @BeforeEach
    void resetChatClient() {
        chatClient.reset();
    }

    @Test
    void ingestionGeneratesTheSummaryAndGlossary() throws Exception {
        Fixture fixture = ingestedDocument();

        mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(fixture.documentId.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.summary").value(SUMMARY_TEXT))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.terms.length()").value(2))
                .andExpect(jsonPath("$.terms[0].term").value("Dynamic programming"))
                .andExpect(jsonPath("$.terms[1].term").value("Optimal substructure"));

        assertEquals(1, chatClient.calls.get(), "ingestion should summarize exactly once");
    }

    @Test
    void readingTheSummaryNeverCallsTheModel() throws Exception {
        Fixture fixture = ingestedDocument();
        assertEquals(1, chatClient.calls.get());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary").value(SUMMARY_TEXT));
        }

        assertEquals(1, chatClient.calls.get(), "GET must be served from the cache");
    }

    // The cache is the whole feature: asking again must not spend another call.
    @Test
    void generatingAgainReturnsTheCachedSummaryWithoutCallingTheModel() throws Exception {
        Fixture fixture = ingestedDocument();

        mockMvc.perform(post(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(SUMMARY_TEXT));

        assertEquals(1, chatClient.calls.get(), "a second request must reuse the cached summary");
    }

    @Test
    void refreshRegeneratesAndReplacesTheGlossary() throws Exception {
        Fixture fixture = ingestedDocument();

        chatClient.nextJson = """
                { "summary": "A revised overview.",
                  "terms": [ { "term": "Memoization", "definition": "Caching subproblem results." } ] }
                """;

        mockMvc.perform(post(summaryUrl(fixture) + "?refresh=true")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("A revised overview."))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.terms[0].term").value("Memoization"));

        assertEquals(2, chatClient.calls.get());
        // Replaced, not appended — the old two terms must be gone.
        assertEquals(1, termRepository.findByDocumentIdOrderByTermIndex(fixture.documentId).size());
    }

    // A document nobody has summarized yet is an ordinary state, not an error: the client needs
    // a null summary back so it can offer to generate one.
    @Test
    void anUnsummarizedDocumentReportsAnEmptySummary() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");
        Document document = savedDocumentWithoutSummary(owner, courseId);

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + document.getId() + "/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.generatedAt").doesNotExist())
                .andExpect(jsonPath("$.terms.length()").value(0));

        assertEquals(0, chatClient.calls.get());
    }

    // Nothing ingested means nothing to summarize — the caller's problem, so 400 rather than a
    // model call that would hallucinate from an empty prompt.
    @Test
    void summarizingADocumentWithNoTextIsRejected() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");
        Document document = savedDocumentWithoutSummary(owner, courseId);

        mockMvc.perform(post("/api/v1/courses/" + courseId + "/documents/" + document.getId() + "/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertEquals(0, chatClient.calls.get(), "no material means no model call");
    }

    @Test
    void aStrangerCannotReadTheSummary() throws Exception {
        Fixture fixture = ingestedDocument();
        String strangerToken = tokenFor(saveUser());

        mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    // Scoping the lookup by course means a document from another course is invisible, not merely
    // forbidden — the 404 leaks nothing about what other courses contain.
    @Test
    void aDocumentFromAnotherCourseIsNotFound() throws Exception {
        Fixture fixture = ingestedDocument();
        String otherCourseId = createCourse(fixture.token, "Unrelated");

        mockMvc.perform(get("/api/v1/courses/" + otherCourseId + "/documents/" + fixture.documentId + "/summary")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateAndBlankTermsAreDropped() throws Exception {
        chatClient.nextJson = """
                { "summary": "Overview.",
                  "terms": [
                    { "term": "Recursion", "definition": "A function calling itself." },
                    { "term": "recursion", "definition": "A duplicate under different casing." },
                    { "term": "   ",       "definition": "Blank term." },
                    { "term": "Base case", "definition": null },
                    { "term": "Base case", "definition": "The terminating branch." }
                  ] }
                """;
        Fixture fixture = ingestedDocument();

        mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.length()").value(2))
                .andExpect(jsonPath("$.terms[0].term").value("Recursion"))
                .andExpect(jsonPath("$.terms[1].term").value("Base case"));
    }

    @Test
    void theGlossaryIsCappedSoItStaysScannable() throws Exception {
        StringBuilder terms = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            terms.append(i == 0 ? "" : ",")
                    .append("{ \"term\": \"Term %d\", \"definition\": \"Definition %d.\" }".formatted(i, i));
        }
        chatClient.nextJson = "{ \"summary\": \"Overview.\", \"terms\": [%s] }".formatted(terms);
        Fixture fixture = ingestedDocument();

        mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.length()").value(12));
    }

    // The column is varchar(120); an over-long "term" is really a sentence, and truncating beats
    // letting the insert blow up mid-ingestion.
    @Test
    void anOverlongTermIsTruncatedToFitTheColumn() throws Exception {
        String longTerm = "T".repeat(400);
        chatClient.nextJson = """
                { "summary": "Overview.",
                  "terms": [ { "term": "%s", "definition": "Far too long to be a term." } ] }
                """.formatted(longTerm);
        Fixture fixture = ingestedDocument();

        List<DocumentTerm> stored = termRepository.findByDocumentIdOrderByTermIndex(fixture.documentId);
        assertEquals(1, stored.size());
        assertEquals(120, stored.get(0).getTerm().length());
    }

    // A provider failure must not undo a perfectly good ingestion: the document stays READY and
    // usable for chat, quizzes and flashcards, just without a summary.
    @Test
    void aFailingModelLeavesTheDocumentReadyWithoutASummary() throws Exception {
        chatClient.failNext = true;
        Fixture fixture = ingestedDocument();

        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/documents/" + fixture.documentId)
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        mockMvc.perform(get(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").doesNotExist());
    }

    // ...and the member can then ask for it explicitly, which is the backfill path.
    @Test
    void aFailedSummaryCanBeGeneratedOnDemandLater() throws Exception {
        chatClient.failNext = true;
        Fixture fixture = ingestedDocument();

        mockMvc.perform(post(summaryUrl(fixture)).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(SUMMARY_TEXT))
                .andExpect(jsonPath("$.terms.length()").value(2));
    }

    // When the model is unreachable the on-demand path says so with 502 rather than pretending.
    @Test
    void anUnreachableModelSurfacesAsBadGateway() throws Exception {
        Fixture fixture = ingestedDocument();
        chatClient.failNext = true;

        mockMvc.perform(post(summaryUrl(fixture) + "?refresh=true")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isBadGateway());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Fixture(String token, String courseId, UUID documentId) { }

    private String summaryUrl(Fixture fixture) {
        return "/api/v1/courses/" + fixture.courseId + "/documents/" + fixture.documentId + "/summary";
    }

    // Uploads a real PDF and runs ingestion inline, which is what triggers summarization. The
    // async/AFTER_COMMIT trigger is exercised elsewhere; here it would only add nondeterminism.
    private Fixture ingestedDocument() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        byte[] pdf = realPdfBytes(
                "Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");
        String documentId = uploadPdf(courseId, token, pdf);

        ingestionService.ingest(UUID.fromString(documentId));
        return new Fixture(token, courseId, UUID.fromString(documentId));
    }

    private Document savedDocumentWithoutSummary(User owner, String courseId) {
        Document document = new Document();
        document.setCourseSpace(courseSpaceRepository.findById(UUID.fromString(courseId)).orElseThrow());
        document.setUploadedBy(owner);
        document.setFilename("empty.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(10);
        document.setSha256("empty" + UUID.randomUUID().toString().replace("-", ""));
        document.setStoragePath("missing/" + UUID.randomUUID());
        document.setStatus(DocumentStatus.READY);
        return documentRepository.saveAndFlush(document);
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user);
    }

    private String createCourse(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCourseRequest(name, "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private byte[] realPdfBytes(String... lines) throws IOException {
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

    private String uploadPdf(String courseId, String token, byte[] bytes) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

}
