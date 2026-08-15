package com.studyloop.backend.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.quiz.QuestionType;
import com.studyloop.backend.quiz.Quiz;
import com.studyloop.backend.quiz.QuizQuestion;
import com.studyloop.backend.quiz.QuizQuestionRepository;
import com.studyloop.backend.quiz.QuizRepository;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The review queue end to end: enrolment, what today's queue contains, how grading reschedules a
// card, and the loop where a missed quiz question becomes a card.
//
// "Tomorrow" is reached by moving a mutable Clock, not by sleeping — the whole point of injecting
// the clock. Everything runs inside a rolled-back transaction, so nothing persists to Supabase.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ReviewQueueTest.MutableClockConfig.class)
class ReviewQueueTest {

    private static final Instant START = Instant.parse("2026-08-15T09:00:00Z");

    // Replaces the production system clock so a test can stand on any given day.
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceDays(long days) {
            now = now.plus(java.time.Duration.ofDays(days));
        }

        void reset(Instant to) {
            now = to;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuizQuestionRepository questionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The clock is a singleton shared by every test in the context, and these tests move it
    // forward — so each one starts by winding it back to the same day.
    @BeforeEach
    void resetClock() {
        clock.reset(START);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private User saveUser() {
        User user = new User();
        user.setEmail("review-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Review User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private CourseSpace saveCourse(User owner, String name) {
        CourseSpace course = new CourseSpace();
        course.setName(name);
        course.setOwner(owner);
        courseSpaceRepository.saveAndFlush(course);

        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.saveAndFlush(membership);
        return course;
    }

    // Saves a card through the API, which is also what enrols it in the queue.
    private UUID createCard(String token, UUID courseId, String front, String back) throws Exception {
        String body = objectMapper.writeValueAsString(new CardBody(front, back));
        String json = mockMvc.perform(post("/api/v1/courses/" + courseId + "/flashcards")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private record CardBody(String front, String back) {
    }

    private record GradeBody(int grade) {
    }

    private String grade(String token, UUID cardId, int grade) throws Exception {
        return mockMvc.perform(post("/api/v1/review/" + cardId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GradeBody(grade))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ── the queue ───────────────────────────────────────────────────────────────────────────

    @Test
    void aNewCardIsDueImmediately() throws Exception {
        User user = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace course = saveCourse(user, "Operating Systems");

        createCard(token, course.getId(), "What is a page fault?", "A trap on an unmapped page.");

        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].front").value("What is a page fault?"))
                .andExpect(jsonPath("$[0].courseName").value("Operating Systems"))
                .andExpect(jsonPath("$[0].dueOn").value(LocalDate.ofInstant(START, ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$[0].repetitions").value(0));
    }

    @Test
    void gradingWellRemovesTheCardFromTodayAndBringsItBackLater() throws Exception {
        User user = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace course = saveCourse(user, "Operating Systems");
        UUID cardId = createCard(token, course.getId(), "front", "back");

        // Two clean recalls: 1 day, then 6.
        grade(token, cardId, 5);
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));

        clock.advanceDays(1);
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        String result = grade(token, cardId, 5);
        assert objectMapper.readTree(result).get("intervalDays").asInt() == 6;

        clock.advanceDays(5);
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
        clock.advanceDays(1);
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void failingACardKeepsItComingBackTomorrow() throws Exception {
        User user = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace course = saveCourse(user, "Operating Systems");
        UUID cardId = createCard(token, course.getId(), "front", "back");

        grade(token, cardId, 5);
        clock.advanceDays(1);

        mockMvc.perform(post("/api/v1/review/" + cardId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GradeBody(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lapsed").value(true))
                .andExpect(jsonPath("$.lapses").value(1))
                .andExpect(jsonPath("$.repetitions").value(0))
                .andExpect(jsonPath("$.intervalDays").value(1));

        clock.advanceDays(1);
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theQueueOnlyEverShowsYourOwnCards() throws Exception {
        User owner = saveUser();
        User classmate = saveUser();
        String ownerToken = jwtService.generateAccessToken(owner);
        String classmateToken = jwtService.generateAccessToken(classmate);
        CourseSpace course = saveCourse(owner, "Operating Systems");

        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(classmate);
        membership.setRole(MembershipRole.MEMBER);
        membershipRepository.saveAndFlush(membership);

        UUID ownerCard = createCard(ownerToken, course.getId(), "owner front", "owner back");

        // Same course, but the classmate's queue is empty and they can't grade someone else's card.
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + classmateToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/v1/review/" + ownerCard)
                        .header("Authorization", "Bearer " + classmateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GradeBody(5))))
                .andExpect(status().isNotFound());
    }

    @Test
    void theQueueCanBeNarrowedToOneCourseAndRejectsCoursesYouAreNotIn() throws Exception {
        User user = saveUser();
        User stranger = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace os = saveCourse(user, "Operating Systems");
        CourseSpace networks = saveCourse(user, "Networks");
        CourseSpace theirs = saveCourse(stranger, "Someone else's course");

        createCard(token, os.getId(), "os front", "os back");
        createCard(token, networks.getId(), "net front", "net back");

        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/review/queue")
                        .param("courseId", os.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].front").value("os front"));
        mockMvc.perform(get("/api/v1/review/due-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.due").value(2));
        mockMvc.perform(get("/api/v1/review/queue")
                        .param("courseId", theirs.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void gradesOutsideZeroToFiveAreRejected() throws Exception {
        User user = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace course = saveCourse(user, "Operating Systems");
        UUID cardId = createCard(token, course.getId(), "front", "back");

        mockMvc.perform(post("/api/v1/review/" + cardId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GradeBody(6))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/review/queue")).andExpect(status().isUnauthorized());
    }

    // ── the loop: a missed quiz question becomes a card ──────────────────────────────────────

    @Test
    void missingAQuizQuestionEnrolsItAsACardAndRetakingDoesNotDuplicateIt() throws Exception {
        User user = saveUser();
        String token = jwtService.generateAccessToken(user);
        CourseSpace course = saveCourse(user, "Operating Systems");

        // A quiz built directly (generation needs the model; the grading path under test doesn't).
        Quiz quiz = new Quiz();
        quiz.setCourseSpace(course);
        quiz.setCreatedBy(user);
        quiz.setTitle("Paging");
        quizRepository.saveAndFlush(quiz);

        QuizQuestion question = new QuizQuestion();
        question.setQuiz(quiz);
        question.setQuestionIndex(0);
        question.setType(QuestionType.SHORT_ANSWER);
        question.setPrompt("What does the TLB cache?");
        question.setExpectedAnswer("Recent virtual-to-physical page translations.");
        question.setExplanation("It sits in front of the page table to avoid a memory access per lookup.");
        questionRepository.saveAndFlush(question);

        String attemptBody = """
                {"answers":[{"questionId":"%s","answerText":""}]}
                """.formatted(question.getId());

        mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/quizzes/" + quiz.getId() + "/attempts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attemptBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.cardsEnrolled").value(1));

        // The missed question is now a card, due today, with the answer on the back.
        mockMvc.perform(get("/api/v1/review/queue").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].front").value("What does the TLB cache?"))
                .andExpect(jsonPath("$[0].back").value(org.hamcrest.Matchers.containsString(
                        "Recent virtual-to-physical page translations.")));

        // Retaking and missing it again must not mint a second card.
        mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/quizzes/" + quiz.getId() + "/attempts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attemptBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardsEnrolled").value(0));

        mockMvc.perform(get("/api/v1/review/due-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.due").value(1));
    }
}
