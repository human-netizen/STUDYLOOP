package com.studyloop.backend.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.course.dto.CreateInviteRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The invite lifecycle: issue (with role rules), preview, join, and the edge cases —
// email binding, idempotency, revocation, and expiry.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InviteFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private CourseInviteRepository inviteRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User saveUser() {
        return saveUser("test-" + UUID.randomUUID() + "@example.com");
    }

    private User saveUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("test-hash");
        user.setDisplayName("Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String createCourse(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCourseRequest(name, "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private JsonNode createInvite(String token, String courseId, CreateInviteRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses/" + courseId + "/invites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void openInviteLetsSecondUserPreviewAndJoin() throws Exception {
        User owner = saveUser();
        User joiner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        String inviteToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(null, null, null)).get("token").asText();

        mockMvc.perform(get("/api/v1/invites/" + inviteToken)
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.courseName").value("Algorithms"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.requiresMatchingEmail").value(false));

        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(courseId))
                .andExpect(jsonPath("$.myRole").value("MEMBER"));

        // The joiner can now read the course they belong to.
        mockMvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk());
    }

    @Test
    void memberCannotCreateInvite() throws Exception {
        User owner = saveUser();
        User member = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        String inviteToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(null, null, null)).get("token").asText();
        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/courses/" + courseId + "/invites")
                        .header("Authorization", "Bearer " + tokenFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateInviteRequest(null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void emailInviteRejectsMismatchedUser() throws Exception {
        User owner = saveUser();
        User stranger = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        String inviteToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest("invited-person@example.com", null, null)).get("token").asText();

        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void emailInviteAllowsMatchingUserAndHidesEmailInPreview() throws Exception {
        User owner = saveUser();
        User invited = saveUser("invited-" + UUID.randomUUID() + "@example.com");
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        String inviteToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(invited.getEmail(), null, null)).get("token").asText();

        // Preview flags that an email is required but never discloses which.
        mockMvc.perform(get("/api/v1/invites/" + inviteToken)
                        .header("Authorization", "Bearer " + tokenFor(invited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresMatchingEmail").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());

        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(invited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("MEMBER"));
    }

    @Test
    void acceptingTwiceKeepsOneMembership() throws Exception {
        User owner = saveUser();
        User joiner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        String inviteToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(null, null, null)).get("token").asText();

        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("MEMBER"));

        mockMvc.perform(get("/api/v1/courses")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void acceptingUnknownTokenReturns404() throws Exception {
        User user = saveUser();

        mockMvc.perform(post("/api/v1/invites/does-not-exist/accept")
                        .header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedInviteCannotBeAccepted() throws Exception {
        User owner = saveUser();
        User joiner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");
        JsonNode invite = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(null, null, null));
        String inviteToken = invite.get("token").asText();
        String inviteId = invite.get("id").asText();

        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/invites/" + inviteId)
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/invites/" + inviteToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void instructorCannotGrantInstructorRole() throws Exception {
        User owner = saveUser();
        User instructor = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        // Owner promotes someone to INSTRUCTOR via an invite.
        String instructorToken = createInvite(tokenFor(owner), courseId,
                new CreateInviteRequest(null, MembershipRole.INSTRUCTOR, null)).get("token").asText();
        mockMvc.perform(post("/api/v1/invites/" + instructorToken + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(instructor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("INSTRUCTOR"));

        // That instructor may invite MEMBERs...
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/invites")
                        .header("Authorization", "Bearer " + tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateInviteRequest(null, MembershipRole.MEMBER, null))))
                .andExpect(status().isCreated());

        // ...but not mint more INSTRUCTORs — that's owner-only.
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/invites")
                        .header("Authorization", "Bearer " + tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateInviteRequest(null, MembershipRole.INSTRUCTOR, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredInviteReturns410() throws Exception {
        User owner = saveUser();
        User joiner = saveUser();
        String courseId = createCourse(tokenFor(owner), "Algorithms");

        // Insert an already-expired invite directly — the API only issues future expiries.
        CourseInvite invite = new CourseInvite();
        invite.setCourseSpace(courseSpaceRepository.findById(UUID.fromString(courseId)).orElseThrow());
        invite.setCreatedBy(owner);
        invite.setToken("expired-" + UUID.randomUUID());
        invite.setRole(MembershipRole.MEMBER);
        invite.setExpiresAt(Instant.now().minusSeconds(3600));
        inviteRepository.saveAndFlush(invite);

        mockMvc.perform(post("/api/v1/invites/" + invite.getToken() + "/accept")
                        .header("Authorization", "Bearer " + tokenFor(joiner)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Invite expired"));
    }
}
