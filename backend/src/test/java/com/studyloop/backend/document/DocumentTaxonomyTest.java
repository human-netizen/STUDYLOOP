package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.course.dto.CreateInviteRequest;
import com.studyloop.backend.security.JwtService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 23.2 — saying what a document is, and reading back what a course is organised by.
//
// Two things this class is here to pin. **The taxonomy is a manager's to set**, on the same rule
// that decides who may retire a document (27.3) and for a stronger reason: a label decides what a
// question aimed at week 3 retrieves for everybody in the course, so a member who could set one
// could aim everybody else's searches. And **the course's vocabulary is read out of the corpus**,
// never declared — which is what keeps the picker from offering fifty-two empty weeks and what
// makes the query-side filter's evidence rule agree with the UI by construction.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class DocumentTaxonomyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownerToken;
    private String courseId;

    @BeforeEach
    void createCourseSpace() throws Exception {
        ownerToken = jwtService.generateAccessToken(saveUser("owner"));
        courseId = createCourse();
    }

    // The inference, arriving through the real upload path rather than through its own unit test:
    // what a filename says is on the row before anything has been extracted.
    @Test
    void anUploadIsFiledFromItsFilename() throws Exception {
        String documentId = upload("Week 03 - Lab 2 - Hashing.pdf");

        mockMvc.perform(get(documentPath(documentId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(3))
                .andExpect(jsonPath("$.category").value("LAB"))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.tags").isEmpty());
    }

    // A filename that says nothing leaves both at their defaults, which is the correct outcome
    // rather than a degraded one — the endpoint below is how a person says what it is.
    @Test
    void aFilenameThatSaysNothingLeavesTheDefaults() throws Exception {
        String documentId = upload("scan_0012.pdf");

        mockMvc.perform(get(documentPath(documentId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").doesNotExist())
                .andExpect(jsonPath("$.category").value("UNCLASSIFIED"));
    }

    @Test
    void aManagerStatesTheWholeTaxonomy() throws Exception {
        String documentId = upload("scan_0012.pdf");

        setTaxonomy(ownerToken, documentId, 4, "TUTORIAL", List.of("Hash Tables", "probing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(4))
                .andExpect(jsonPath("$.category").value("TUTORIAL"));
    }

    // Normalisation, asserted on the way back out: lower-cased, whitespace collapsed, duplicates
    // gone. Without it "Hash Tables", "hash tables" and "hash  tables" are three tags on the same
    // document and three entries in the course's picker.
    @Test
    void tagsAreNormalisedAndDeduplicated() throws Exception {
        String documentId = upload("scan_0012.pdf");

        setTaxonomy(ownerToken, documentId, null, "READING",
                List.of("Hash Tables", "hash  tables", "  HASH TABLES  ", "probing", ""))
                .andExpect(status().isOk());

        JsonNode tags = readTree(mockMvc.perform(
                        get(documentPath(documentId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())).get("tags");

        assertEquals(2, tags.size(), () -> "three spellings of one tag are one tag: " + tags);
        assertTrue(tags.toString().contains("hash tables"));
    }

    // A PUT states the whole target state, so a body that omits a field clears it. This is the
    // difference from 27.4's PATCH and it is the only way to spell "this is not a week 4 document
    // after all".
    @Test
    void anOmittedFieldIsCleared() throws Exception {
        String documentId = upload("Week 03 - Lab 2.pdf");

        setTaxonomy(ownerToken, documentId, null, null, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").doesNotExist())
                .andExpect(jsonPath("$.category").value("UNCLASSIFIED"))
                .andExpect(jsonPath("$.tags").isEmpty());
    }

    // A member may read the library and may not aim everybody's searches at part of it.
    @Test
    void aMemberCannotLabelCourseMaterial() throws Exception {
        String documentId = upload("scan_0012.pdf");
        String memberToken = jwtService.generateAccessToken(saveUser("member"));
        join(memberToken);

        setTaxonomy(memberToken, documentId, 4, "LECTURE", List.of())
                .andExpect(status().isForbidden());
    }

    // Bounded here as well as by the column's check constraint, so a bad value is a 400 naming the
    // field rather than a 500 carrying a constraint name out of Postgres.
    @Test
    void aWeekOutsideTheRangeIsRefusedAsABadRequest() throws Exception {
        String documentId = upload("scan_0012.pdf");

        setTaxonomy(ownerToken, documentId, 99, "LECTURE", List.of())
                .andExpect(status().isBadRequest());
    }

    // The course's own vocabulary, and the assertion that matters is what is *absent*: weeks
    // nobody has filed anything under are not offered.
    @Test
    void theCourseVocabularyIsReadOutOfTheCorpus() throws Exception {
        setTaxonomy(ownerToken, upload("a.pdf"), 3, "LECTURE", List.of("hashing"));
        setTaxonomy(ownerToken, upload("b.pdf"), 7, "LAB", List.of("hashing", "treaps"));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/taxonomy")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks", org.hamcrest.Matchers.contains(3, 7)))
                .andExpect(jsonPath("$.tags", org.hamcrest.Matchers.contains("hashing", "treaps")))
                .andExpect(jsonPath("$.categories", org.hamcrest.Matchers.hasSize(2)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String documentPath(String documentId) {
        return "/api/v1/courses/" + courseId + "/documents/" + documentId;
    }

    private org.springframework.test.web.servlet.ResultActions setTaxonomy(
            String token, String documentId, Integer week, String category, List<String> tags)
            throws Exception {
        return mockMvc.perform(put(documentPath(documentId) + "/taxonomy")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TaxonomyBody(week, category, tags))));
    }

    private JsonNode readTree(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String upload(String filename) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", onePagePdf()))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void join(String memberToken) throws Exception {
        String invite = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/courses/" + courseId + "/invites")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new CreateInviteRequest(null, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/invites/" + invite + "/accept")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
    }

    private User saveUser(String who) {
        User user = new User();
        user.setEmail(who + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName(who);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Data structures", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // Distinct bytes per call, because `(course_space_id, sha256)` is a database guarantee and two
    // uploads of identical bytes are one document by design.
    private byte[] onePagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle(UUID.randomUUID().toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private record TaxonomyBody(Integer week, String category, List<String> tags) { }
}
