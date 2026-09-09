package com.studyloop.backend.flashcard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.course.dto.CreateInviteRequest;
import com.studyloop.backend.flashcard.dto.CreateFlashcardRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 29.1's export, at the endpoint.
//
// FlashcardCsvTest asserts the bytes; this asserts the two things only the HTTP layer decides —
// that the response is a file rather than JSON, and that a deck is exported to the person whose
// deck it is. The second matters more than it looks: `list` was already owner-scoped, and an
// export written as "the same data, in another format" is exactly the kind of second read path
// that quietly forgets a predicate.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlashcardExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theDeckComesBackAsAFileAndNotAsJson() throws Exception {
        User owner = saveUser();
        String token = tokenFor(owner);
        String courseId = createCourse(token);
        saveCard(courseId, token, "What is a heap?", "A complete binary tree, smallest at the root");

        String csv = mockMvc.perform(get("/api/v1/courses/" + courseId + "/flashcards/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith("#separator:Comma");
        assertThat(csv).contains(
                "What is a heap?,\"A complete binary tree, smallest at the root\"");
    }

    // A card with a comma in it is the ordinary case, so the endpoint is asserted on one: a body
    // that reached the client mis-quoted would be a broken import rather than an error anybody sees.
    @Test
    void anotherMembersDeckIsNotInTheFile() throws Exception {
        User owner = saveUser();
        String ownerToken = tokenFor(owner);
        String courseId = createCourse(ownerToken);
        saveCard(courseId, ownerToken, "Owner's question", "Owner's answer");

        User classmate = saveUser();
        String classmateToken = tokenFor(classmate);
        join(courseId, ownerToken, classmateToken);
        saveCard(courseId, classmateToken, "Classmate's question", "Classmate's answer");

        String csv = mockMvc.perform(get("/api/v1/courses/" + courseId + "/flashcards/export")
                        .header("Authorization", "Bearer " + classmateToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("Classmate's question");
        assertThat(csv).doesNotContain("Owner's question");
    }

    @Test
    void aStrangerCannotExportACourseTheyAreNotIn() throws Exception {
        User owner = saveUser();
        String courseId = createCourse(tokenFor(owner));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/flashcards/export")
                        .header("Authorization", "Bearer " + tokenFor(saveUser())))
                .andExpect(status().isForbidden());
    }

    // ── harness ───────────────────────────────────────────────────────────────────────────────

    private void saveCard(String courseId, String token, String front, String back) throws Exception {
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/flashcards")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateFlashcardRequest(front, back, null, null))))
                .andExpect(status().isCreated());
    }

    private void join(String courseId, String ownerToken, String joinerToken) throws Exception {
        String invite = mockMvc.perform(post("/api/v1/courses/" + courseId + "/invites")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateInviteRequest(null, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(invite).get("token").asText();

        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + joinerToken))
                .andExpect(status().isOk());
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("export-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user);
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Data Structures", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
