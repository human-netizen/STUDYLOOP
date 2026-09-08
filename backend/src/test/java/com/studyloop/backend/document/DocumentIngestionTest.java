package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.security.JwtService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The ingestion state machine end to end, driven synchronously (the async/after-commit
// trigger is exercised by manual smoke tests): a real PDF extracts, chunks, and reaches
// READY, while unreadable or unparseable bytes land in FAILED with a recorded reason.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// Shares StubAiConfig with the other document tests: the state machine is what's under test, so
// the embedding and summary steps run against stubs rather than real providers.
@Import(StubAiConfig.class)
class DocumentIngestionTest {

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
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private DocumentIngestionService ingestionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // A genuine single-page PDF with extractable text.
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

    // The same, with one numbered line per page, for the tests that need pages to tell apart.
    private byte[] realMultiPagePdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int number = 1; number <= pages; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText("Chapter " + number + " opens with a definition of a heap.");
                    content.newLineAtOffset(0, -16);
                    content.showText("A heap keeps its smallest element at the root at all times.");
                    content.endText();
                }
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
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void pipelineExtractsChunksAndReachesReady() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        byte[] pdf = realPdfBytes(
                "Binary search halves the search interval each step.",
                "It requires the input array to be sorted beforehand.",
                "Its time complexity is logarithmic in the array size.");
        String docId = uploadPdf(courseId, token, pdf);

        // Run ingestion inline so the state machine is exercised deterministically inside
        // this transaction (the real trigger is async + AFTER_COMMIT).
        ingestionService.ingest(UUID.fromString(docId));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.pageCount").value(1));

        assertTrue(chunkRepository.countByDocumentId(UUID.fromString(docId)) >= 1,
                "expected at least one chunk to be persisted");
    }

    @Test
    void pipelineMarksFailedForUnparseablePdf() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        // Bytes that claim to be a PDF but aren't a valid document — extraction must fail.
        byte[] notReallyPdf = "%PDF-1.4\nnot a real pdf".getBytes(StandardCharsets.UTF_8);
        String docId = uploadPdf(courseId, token, notReallyPdf);

        ingestionService.ingest(UUID.fromString(docId));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    @Test
    void pipelineMarksFailedWhenStoredBytesMissing() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        // A document row whose backing file was never written — extraction can't read it.
        Document doc = new Document();
        doc.setCourseSpace(courseSpaceRepository.findById(UUID.fromString(courseId)).orElseThrow());
        doc.setUploadedBy(owner);
        doc.setFilename("ghost.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(10);
        doc.setSha256("ghost" + UUID.randomUUID().toString().replace("-", ""));
        doc.setStoragePath("missing/" + UUID.randomUUID());
        doc.setStatus(DocumentStatus.UPLOADED);
        Document saved = documentRepository.saveAndFlush(doc);

        ingestionService.ingest(saved.getId());

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + saved.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    // ── Phase 25.1: the page range ──────────────────────────────────────────────────────────

    @Test
    void aPageRangeIsStoredOnTheRowAndOnlyItsPagesAreIngested() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "book.pdf", "application/pdf",
                                realMultiPagePdf(6)))
                        .param("firstPage", "2")
                        .param("lastPage", "4")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.firstPage").value(2))
                .andExpect(jsonPath("$.lastPage").value(4))
                .andReturn().getResponse().getContentAsString();
        String docId = objectMapper.readTree(body).get("id").asText();

        ingestionService.ingest(UUID.fromString(docId));

        // Three pages ingested out of six, and the range survives on the row so a later re-ingest
        // reads the same slice without being told again.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.firstPage").value(2));

        // The chunks carry the source document's own page numbers, not 1-3. This is the assertion
        // that would catch a slice that renumbered itself: every count above would still pass.
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdAndModalityOrderByChunkIndex(
                UUID.fromString(docId), ChunkModality.TEXT);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getPageNumber() >= 2),
                "expected every chunk to sit on page 2 or later");
    }

    @Test
    void aRangeThatIsNotARangeIsRefusedAtTheEdge() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");
        byte[] pdf = realMultiPagePdf(4);

        // The two mistakes a client can make without ever opening the file.
        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "book.pdf", "application/pdf", pdf))
                        .param("firstPage", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "book.pdf", "application/pdf", pdf))
                        .param("firstPage", "9")
                        .param("lastPage", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUploadWithNoRangeIsUnchanged() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        String docId = uploadPdf(courseId, token, realMultiPagePdf(3));
        ingestionService.ingest(UUID.fromString(docId));

        // The regression guard on the whole of 25.1: the common upload still stores no range and
        // reads the whole document.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.firstPage").doesNotExist())
                .andExpect(jsonPath("$.lastPage").doesNotExist());
    }

    // ── Phase 25.2: progress ────────────────────────────────────────────────────────────────

    @Test
    void progressReachesOneHundredAndTheStageSaysSo() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        String docId = uploadPdf(courseId, token, realMultiPagePdf(2));

        // Before the pipeline runs: nothing has happened, and the row says so rather than saying
        // nothing.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.progress").value(0));

        ingestionService.ingest(UUID.fromString(docId));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.stage").isNotEmpty())
                .andExpect(jsonPath("$.degradedPages").value(0));
    }

    @Test
    void aFailedIngestHoldsThePercentageItReachedRatherThanResetting() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        byte[] notReallyPdf = "%PDF-1.4\nnot a real pdf".getBytes(StandardCharsets.UTF_8);
        String docId = uploadPdf(courseId, token, notReallyPdf);
        ingestionService.ingest(UUID.fromString(docId));

        // Where it stopped is the most useful thing a failed row can say — a document that failed
        // at 92% is a different problem from one that failed at 5% — so markFailed leaves the
        // number alone. Extraction is where this one died, and EXTRACTING owns the floor of 5.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.progress").value(IngestionProgress.EXTRACTING_FLOOR));
    }
}
