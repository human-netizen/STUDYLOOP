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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
}
