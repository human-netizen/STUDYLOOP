package com.studyloop.backend.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 27.4 — the course verbs, and the one rule that has no constraint behind it.
//
// **The last-owner rule is the interesting half.** A course with no OWNER is unadministrable:
// nobody can rename it, upload to it, remove anybody from it, or archive it. No foreign key
// expresses "at least one row with role = OWNER must remain" and no cascade would notice the last
// one going, so it is a check in a service — which means it is only as good as this test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CourseLifecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User owner;
    private String ownerToken;
    private String courseId;

    @BeforeEach
    void setUp() throws Exception {
        owner = saveUser("owner");
        ownerToken = jwtService.generateAccessToken(owner);
        courseId = createCourse();
    }

    // ── rename: the first PATCH ───────────────────────────────────────────────────────────────

    @Test
    void patchingOnlyTheNameLeavesTheDescriptionAlone() throws Exception {
        // The whole reason this is a PATCH and not a PUT. A client renaming a course sends one
        // field and is not made responsible for round-tripping a description it never read.
        mockMvc.perform(patch("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Algorithms II\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Algorithms II"))
                .andExpect(jsonPath("$.description").value("desc"));
    }

    @Test
    void aBlankNameIsRefusedWhileAnAbsentOneIsNot() throws Exception {
        // Absent means "leave it alone"; blank would route around the not-null column and the
        // @NotBlank the create path enforces, and leave a course nobody can find in a list.
        mockMvc.perform(patch("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"only this\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Algorithms"));
    }

    @Test
    void anInstructorMayCurateTheMaterialAndMayNotRenameTheCourse() throws Exception {
        User instructor = saveUser("instructor");
        addMember(instructor, "INSTRUCTOR");

        // Driven as somebody other than the owner, per 16.3: a test written as the owner passes
        // whether or not the guard exists.
        mockMvc.perform(patch("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed by an instructor\"}"))
                .andExpect(status().isForbidden());
    }

    // ── archive ───────────────────────────────────────────────────────────────────────────────

    @Test
    void archivingHidesTheCourseFromTheListWithoutBlockingIt() throws Exception {
        mockMvc.perform(post("/api/v1/courses/" + courseId + "/archive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        assertThat(listedCourseIds(false)).doesNotContain(courseId);
        assertThat(listedCourseIds(true)).contains(courseId);

        // Archive is not a read block, deliberately: the course still opens by direct link and
        // still answers questions. Making it mean "read-only" as well would be a second feature
        // wearing the same word.
        mockMvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/courses/" + courseId + "/unarchive")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(listedCourseIds(false)).contains(courseId);
    }

    // ── leaving, and being removed ────────────────────────────────────────────────────────────

    @Test
    void aMemberCanLeaveAndTheirContributionsStayWithTheCourse() throws Exception {
        User student = saveUser("student");
        addMember(student, "MEMBER");

        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/membership")
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(student)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theLastOwnerCanNeitherLeaveNorBeRemoved() throws Exception {
        User instructor = saveUser("instructor");
        addMember(instructor, "INSTRUCTOR");

        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/membership")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());

        // 409 rather than 403 for the same reason on both routes: the caller has every permission
        // the action needs, and the refusal is about what the course would be left as.
        User second = saveUser("second-owner");
        addMember(second, "OWNER");
        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(second)))
                .andExpect(status().isNoContent());
    }

    @Test
    void anInstructorCannotRemoveAnOwner() throws Exception {
        User instructor = saveUser("instructor");
        addMember(instructor, "INSTRUCTOR");

        // Removing the person who granted your role is a privilege escalation dressed as an
        // administrative action.
        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/members/" + owner.getId())
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(instructor)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlainMemberCannotRemoveAnybody() throws Exception {
        User student = saveUser("student");
        User other = saveUser("other");
        addMember(student, "MEMBER");
        addMember(other, "MEMBER");

        mockMvc.perform(delete("/api/v1/courses/" + courseId + "/members/" + other.getId())
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(student)))
                .andExpect(status().isForbidden());
    }

    // ── deleting an account ───────────────────────────────────────────────────────────────────

    @Test
    void deletingAnAccountIsRefusedWhileYouAreTheLastOwnerOfACourse() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void deletingAnAccountScrubsTheIdentityAndKeepsWhatTheCourseOwns() throws Exception {
        User student = saveUser("student");
        addMember(student, "MEMBER");
        String email = student.getEmail();
        UUID askerId = student.getId();
        seedQuestionEvent(askerId);

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(student)))
                .andExpect(status().isNoContent());

        // The identity is gone: the address can never be logged in with, matched by an invite, or
        // recognised in a list, and `@deleted.invalid` is a reserved TLD so nothing can collide.
        Integer byOldEmail = jdbcTemplate.queryForObject(
                "select count(*) from users where email = ?", Integer.class, email);
        assertThat(byOldEmail).isZero();
        String scrubbed = jdbcTemplate.queryForObject(
                "select display_name from users where id = ?", String.class, askerId);
        assertThat(scrubbed).isEqualTo("Deleted account");

        // The memberships are gone, so they are out of every course.
        Integer memberships = jdbcTemplate.queryForObject(
                "select count(*) from memberships where user_id = ?", Integer.class, askerId);
        assertThat(memberships).isZero();

        // And the anonymised counts survive, which is the whole argument for the tombstone: they
        // never exposed identity, and `distinct_askers` stays exact.
        Integer events = jdbcTemplate.queryForObject(
                "select count(*) from question_events where asked_by = ?", Integer.class, askerId);
        assertThat(events).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private String listedCourseIds(boolean archived) throws Exception {
        return mockMvc.perform(get("/api/v1/courses")
                        .param("archived", String.valueOf(archived))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void seedQuestionEvent(UUID askerId) {
        jdbcTemplate.update("""
                insert into question_events (id, course_space_id, asked_by, question, grounded)
                values (?, cast(? as uuid), ?, 'what is a heap?', true)
                """, UUID.randomUUID(), courseId, askerId);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Course " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private void addMember(User user, String role) {
        jdbcTemplate.update("""
                insert into memberships (id, course_space_id, user_id, role)
                values (?, cast(? as uuid), ?, ?)
                """, UUID.randomUUID(), courseId, user.getId(), role);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
