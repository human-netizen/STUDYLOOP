package com.studyloop.backend.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Course CRUD and the membership visibility rules: you own what you create, you only see
// your own courses, and reading someone else's is 403 (exists) vs 404 (doesn't).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

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
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest(name, "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void createCourseMakesCallerOwner() throws Exception {
        User user = saveUser();

        mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "CSE 101"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Algorithms"))
                .andExpect(jsonPath("$.ownerId").value(user.getId().toString()))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createCourseRejectsBlankName() throws Exception {
        User user = saveUser();

        mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("  ", "desc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsOnlyMyCourses() throws Exception {
        User alice = saveUser();
        User bob = saveUser();
        createCourse(tokenFor(alice), "Alice A");
        createCourse(tokenFor(alice), "Alice B");
        createCourse(tokenFor(bob), "Bob A");

        mockMvc.perform(get("/api/v1/courses")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/v1/courses")
                        .header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getOwnCourseReturnsIt() throws Exception {
        User user = saveUser();
        String courseId = createCourse(tokenFor(user), "Algorithms");

        mockMvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.myRole").value("OWNER"));
    }

    @Test
    void getCourseAsNonMemberReturns403() throws Exception {
        User owner = saveUser();
        User stranger = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        mockMvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + tokenFor(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void getMissingCourseReturns404() throws Exception {
        User user = saveUser();

        mockMvc.perform(get("/api/v1/courses/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Course not found"));
    }
}
