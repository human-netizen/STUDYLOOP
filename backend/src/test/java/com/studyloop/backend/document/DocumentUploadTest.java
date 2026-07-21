package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The upload endpoint: who may add course materials, dedup on re-upload, content-type and
// empty-file rejection, and member-scoped listing/reads.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentUploadTest {

    // Point the storage root at the OS temp dir so tests never write into the repo.
    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("studyloop.storage.documents-dir",
                () -> System.getProperty("java.io.tmpdir") + "/studyloop-test-docs");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

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

    // Adds a plain MEMBER to a course directly, bypassing the invite flow.
    private void addMember(String courseId, User user, MembershipRole role) {
        Membership membership = new Membership();
        membership.setCourseSpace(courseSpaceRepository.findById(UUID.fromString(courseId)).orElseThrow());
        membership.setUser(user);
        membership.setRole(role);
        membershipRepository.saveAndFlush(membership);
    }

    private MockMultipartFile pdf(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/pdf",
                ("%PDF-1.4\n" + content).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ownerUploadStoresDocumentAndReturns202() throws Exception {
        User owner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("lecture-1.pdf", "week one"))
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.filename").value("lecture-1.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.sha256").exists())
                .andExpect(jsonPath("$.uploadedById").value(owner.getId().toString()));
    }

    @Test
    void reUploadingSameFileReturnsExistingWith200() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token, "Algorithms");

        String firstId = objectMapper.readTree(
                        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                                        .file(pdf("lecture-1.pdf", "same bytes"))
                                        .header("Authorization", "Bearer " + token))
                                .andExpect(status().isAccepted())
                                .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        // Same content (even a different filename) dedupes to the existing document.
        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("renamed.pdf", "same bytes"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void nonPdfUploadReturns415() throws Exception {
        User owner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain",
                "just text".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(txt)
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported document type"));
    }

    @Test
    void emptyFileReturns400() throws Exception {
        User owner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf",
                new byte[0]);
        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(empty)
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void plainMemberCannotUpload() throws Exception {
        User owner = saveUser();
        User member = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        addMember(courseId, member, MembershipRole.MEMBER);

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("lecture-1.pdf", "member upload"))
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMemberCannotUpload() throws Exception {
        User owner = saveUser();
        User stranger = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("lecture-1.pdf", "stranger upload"))
                        .header("Authorization", "Bearer " + tokenFor(stranger)))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        User owner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("lecture-1.pdf", "anon")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void memberCanListButGetMissingDocumentIs404() throws Exception {
        User owner = saveUser();
        User member = saveUser();
        String ownerToken = tokenFor(owner);
        String courseId = createCourse(ownerToken, "Algorithms");
        addMember(courseId, member, MembershipRole.MEMBER);

        mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(pdf("lecture-1.pdf", "content"))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());

        // A plain member may read the course's documents.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // An unknown document id, scoped to the course, is a 404.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }
}
