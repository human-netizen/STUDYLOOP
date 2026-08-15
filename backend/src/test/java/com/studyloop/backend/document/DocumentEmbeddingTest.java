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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Verifies the embedding step writes a vector into the pgvector column for every chunk, using a
// stub EmbeddingClient (no real API key needed, so this runs in CI). StubAiConfig is shared with
// the other document tests so all three run in one cached context — see the note there about the
// pooler's client budget.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class DocumentEmbeddingTest {

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

    @Test
    void embeddingStepPopulatesVectorForEveryChunk() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        byte[] pdf = realPdfBytes(
                "Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");
        String docId = uploadPdf(courseId, token, pdf);

        ingestionService.ingest(UUID.fromString(docId));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        UUID documentId = UUID.fromString(docId);
        Integer chunkCount = jdbcTemplate.queryForObject(
                "select count(*) from document_chunks where document_id = ?", Integer.class, documentId);
        Integer embeddedCount = jdbcTemplate.queryForObject(
                "select count(*) from document_chunks where document_id = ? and embedding is not null",
                Integer.class, documentId);

        assertTrue(chunkCount != null && chunkCount >= 1, "expected at least one chunk");
        assertEquals(chunkCount, embeddedCount, "every chunk should have an embedding");
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

    private String uploadPdf(String courseId, String token, byte[] bytes) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
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
}
