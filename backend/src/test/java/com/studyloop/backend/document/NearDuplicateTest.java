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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 29.2 — the near-duplicate report's mechanism.
//
// **What this can prove and what it cannot.** The suite runs on StubAiConfig's embedder, which
// keys a vector on the text's content: identical text lands at cosine 1.0 and anything else lands
// essentially orthogonal. That is exactly enough to assert the machinery — the query samples the
// right chunks, the coverage arithmetic is right, the columns are written, cleared and surfaced,
// and nothing is refused — and it is exactly *not* enough to say where between 1.0 and 0.0 the
// threshold belongs, because in this space there is nothing in between.
//
// The number itself is measured in NearDuplicateThresholdTest, which needs a real embedder and is
// gated behind a system property. Keeping the two apart is the point: this one runs on every push
// and would go on passing if the threshold were nonsense, and that is stated here rather than left
// for somebody to infer from a green build.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class NearDuplicateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEAPS = List.of(
            "A binary heap keeps its smallest element at the root at all times.",
            "Insertion appends the new element and sifts it up toward the root.",
            "Removing the minimum swaps the last element in and sifts it down.",
            "Both operations touch one root-to-leaf path, so both are logarithmic.");

    private static final List<String> SKIPLISTS = List.of(
            "A skiplist is a sequence of linked lists stacked on top of each other.",
            "Each element is promoted to the next list up by a coin flip.",
            "Searching walks right along a list and drops down when it would overshoot.",
            "The expected height is logarithmic, which is what makes the search fast.");

    // The case the sha256 constraint cannot see: the same lecture, exported twice. Identical text,
    // different bytes — here because the PDF metadata differs, in the world because it came out of
    // PowerPoint the second time.
    @Test
    void theSameLectureExportedTwiceIsReported() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        UUID first = ingest(courseId, token, "heaps.pdf", pdf("first export", HEAPS));
        UUID second = ingest(courseId, token, "heaps.pdf", pdf("second export", HEAPS));

        // Different bytes, so the exact-duplicate constraint let both in — which is the premise of
        // the whole sub-phase, asserted rather than assumed.
        assertThat(sha256Of(first)).isNotEqualTo(sha256Of(second));

        assertThat(nearDuplicateOf(second)).isEqualTo(first);
        assertThat(nearDuplicateScore(second)).isEqualTo(1.0);
    }

    // The report is a remark. Nothing about the document is different because of it.
    @Test
    void theDuplicateIsStillIngestedAndAnswerable() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        ingest(courseId, token, "heaps.pdf", pdf("first export", HEAPS));
        UUID second = ingest(courseId, token, "heaps.pdf", pdf("second export", HEAPS));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.nearDuplicateOfId").exists());
    }

    @Test
    void aDifferentLectureIsNotReported() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        ingest(courseId, token, "heaps.pdf", pdf("only export", HEAPS));
        UUID other = ingest(courseId, token, "skiplists.pdf", pdf("only export", SKIPLISTS));

        assertThat(nearDuplicateOf(other)).isNull();
        assertThat(nearDuplicateScore(other)).isNull();
    }

    // The first document in a course has nothing to be compared against, which is the case a query
    // written around a join gets wrong.
    @Test
    void theFirstDocumentInACourseIsNotReported() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        UUID only = ingest(courseId, token, "heaps.pdf", pdf("only export", HEAPS));

        assertThat(nearDuplicateOf(only)).isNull();
    }

    // Two courses are two corpora. A shared textbook uploaded to both is not a duplicate in either.
    @Test
    void aMatchInAnotherCourseIsNotAMatch() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String algorithms = createCourse(token, "Algorithms");
        String structures = createCourse(token, "Data Structures");

        ingest(algorithms, token, "heaps.pdf", pdf("first export", HEAPS));
        UUID elsewhere = ingest(structures, token, "heaps.pdf", pdf("second export", HEAPS));

        assertThat(nearDuplicateOf(elsewhere)).isNull();
    }

    // A verdict is a statement about the corpus as it stands, so re-ingesting has to re-decide it
    // — including deciding that there is nothing to say. The failure this guards against is the one
    // every write-on-success cache has: the row keeps last week's answer because the code only ever
    // writes a match and never writes its absence.
    //
    // Deleting the document it pointed at would not test this. `near_duplicate_of` is declared
    // `on delete set null`, so the database would clear the column on its own and the assertion
    // would pass with the detector deleted entirely. A stale verdict has to be planted instead.
    @Test
    void reingestingClearsAVerdictThatNoLongerHolds() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Data Structures");

        UUID heaps = ingest(courseId, token, "heaps.pdf", pdf("only export", HEAPS));
        UUID skiplists = ingest(courseId, token, "skiplists.pdf", pdf("only export", SKIPLISTS));
        assertThat(nearDuplicateOf(heaps)).isNull();

        jdbc.update("update documents set near_duplicate_of = ?, near_duplicate_score = 0.91 where id = ?",
                skiplists, heaps);
        assertThat(nearDuplicateOf(heaps)).isEqualTo(skiplists);

        ingestionService.ingest(heaps);

        assertThat(nearDuplicateOf(heaps)).isNull();
        assertThat(nearDuplicateScore(heaps)).isNull();
    }

    // ── harness ───────────────────────────────────────────────────────────────────────────────

    private UUID ingest(String courseId, String token, String filename, byte[] bytes) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        // Inline, for the reason DocumentIngestionTest runs it inline: the real trigger is async
        // and after-commit, and this test's transaction never commits.
        ingestionService.ingest(id);
        return id;
    }

    // Same words, different bytes. The subject line never reaches the extracted text, so the chunks
    // — and therefore the vectors — are identical, which is what a re-export of one lecture looks
    // like to everything downstream of extraction.
    private byte[] pdf(String subject, List<String> lines) throws IOException {
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
            document.getDocumentInformation().setSubject(subject);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    // Read back through JDBC rather than the repository: the detector writes these two columns with
    // a JdbcTemplate update, so a JPA read inside the same transaction would answer from the
    // persistence context and never see it.
    private UUID nearDuplicateOf(UUID documentId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select near_duplicate_of, near_duplicate_score from documents where id = ?", documentId);
        Object value = row.get("near_duplicate_of");
        return value == null ? null : UUID.fromString(value.toString());
    }

    private Double nearDuplicateScore(UUID documentId) {
        return jdbc.queryForObject(
                "select near_duplicate_score from documents where id = ?", Double.class, documentId);
    }

    private String sha256Of(UUID documentId) {
        return jdbc.queryForObject("select sha256 from documents where id = ?", String.class, documentId);
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("dup-" + UUID.randomUUID() + "@example.com");
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
}
