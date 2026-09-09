package com.studyloop.backend.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 28.5 — quiz me on what I keep getting wrong.
//
// Build (a): the questions already missed, re-served. **The two assertions that matter are that it
// is the caller's own record and nobody else's, and that building the set costs no provider call.**
// The second is easy to lose by accident — this is a study feature in a product where most study
// features generate something — so it is asserted on the call counter rather than on the response.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class WrongAnswerQuizTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private QuizQuestionRepository questionRepository;

    @Autowired
    private RecordingChatClient chatClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User student;
    private String token;
    private CourseSpace course;
    private QuizQuestion missed;
    private QuizQuestion answered;

    @BeforeEach
    void aQuizWithOneQuestionMissed() {
        student = saveUser("student");
        token = jwtService.generateAccessToken(student);
        course = saveCourse(student, "Operating Systems");

        // Built directly rather than generated: generation needs the model, and the selection under
        // test does not care how the questions got there.
        Quiz quiz = new Quiz();
        quiz.setCourseSpace(course);
        quiz.setCreatedBy(student);
        quiz.setTitle("Paging");
        quizRepository.saveAndFlush(quiz);

        missed = saveQuestion(quiz, 0, "What does the TLB cache?",
                "Recent virtual-to-physical page translations.");
        answered = saveQuestion(quiz, 1, "What is a page fault?",
                "A reference to a page that is not resident in memory.");
        chatClient.reset();
    }

    // The whole feature, and the free half of it: the set is a read of rows the product has been
    // writing since Phase 7.2, so nothing is generated and nothing is billed.
    @Test
    void buildingTheSetCostsNoProviderCall() throws Exception {
        recordAttempt(missed, false);

        JsonNode set = practiceSet(token);

        assertEquals(0, chatClient.calls.get(),
                "re-serving questions that already exist must not call a model");
        assertEquals(1, set.get("questions").size());
        assertEquals("What does the TLB cache?", set.get("questions").get(0).get("prompt").asText());
    }

    // Only what was actually missed — a question answered correctly is not revision.
    @Test
    void onlyTheMissedQuestionIsServed() throws Exception {
        recordAttempt(missed, false);
        recordAttempt(answered, true);

        JsonNode questions = practiceSet(token).get("questions");

        assertEquals(1, questions.size());
        assertEquals(missed.getId().toString(), questions.get(0).get("id").asText());
    }

    // **Driven as a classmate, which is the only honest way to test it.** A quiz is shared with the
    // whole course, so a selection that filtered on the quiz rather than on the attempt's owner
    // would hand every member everybody else's mistakes — and would look perfectly correct from the
    // seat of the person who made them.
    @Test
    void aClassmateSeesTheirOwnMistakesAndNotYours() throws Exception {
        recordAttempt(missed, false);

        User classmate = saveUser("classmate");
        addMember(classmate);

        assertEquals(0, practiceSet(jwtService.generateAccessToken(classmate)).get("questions").size(),
                "somebody else's wrong answers are not your revision list");
    }

    // A lapsed card counts too, even with no failed attempt behind it: the two tables disagree in
    // both directions, and a card failed repeatedly long after the quiz is exactly the question
    // worth asking again.
    @Test
    void aLapsedCardCountsEvenWithoutAFailedAttempt() throws Exception {
        UUID flashcardId = enrolCard(missed);
        jdbcTemplate.update("update review_states set lapses = 2 where flashcard_id = ?", flashcardId);

        JsonNode questions = practiceSet(token).get("questions");

        assertEquals(1, questions.size());
        assertEquals(missed.getId().toString(), questions.get(0).get("id").asText());
    }

    // The set is not a quiz, and grading it records no attempt row — there is no quiz for one to
    // belong to. Asserted on the table, because the response looks the same either way.
    @Test
    void gradingThePracticeSetRecordsNoAttempt() throws Exception {
        recordAttempt(missed, false);
        int before = attemptCount();

        mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/quizzes/wrong-answers/attempts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[{"questionId":"%s","answerText":"Recent virtual-to-physical page translations."}]}
                                """.formatted(missed.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.attemptId").doesNotExist())
                .andExpect(jsonPath("$.quizId").doesNotExist());

        assertEquals(before, attemptCount(),
                "a practice round is not an attempt on any quiz and must not write one");
    }

    // **The constraint that makes re-serving safe.** These are the original question rows, so
    // missing one again finds the card `uq_flashcards_owner_quiz_question` already holds. Copying
    // the questions into a new quiz — the obvious build — would defeat that silently, one duplicate
    // card per practice round.
    @Test
    void missingAQuestionAgainDoesNotMintASecondCard() throws Exception {
        recordAttempt(missed, false);
        enrolCard(missed);
        int before = cardCount();

        mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/quizzes/wrong-answers/attempts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[{"questionId":"%s","answerText":""}]}
                                """.formatted(missed.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.cardsEnrolled").value(0));

        assertEquals(before, cardCount(), "the same question must not enrol twice");
    }

    // Nothing missed is the good outcome, and it is an empty set rather than an error.
    @Test
    void aCleanRecordProducesAnEmptySet() throws Exception {
        recordAttempt(answered, true);

        assertEquals(0, practiceSet(token).get("questions").size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode practiceSet(String asToken) throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/quizzes/wrong-answers")
                        .header("Authorization", "Bearer " + asToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // An attempt row and its answer, written directly: the grading path is tested elsewhere, and
    // what this class needs is the record it leaves behind.
    private void recordAttempt(QuizQuestion question, boolean correct) {
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into quiz_attempts (id, quiz_id, user_id, score, total)
                values (?, ?, ?, ?, ?)
                """, attemptId, question.getQuiz().getId(), student.getId(), correct ? 1 : 0, 1);
        jdbcTemplate.update("""
                insert into quiz_attempt_answers (id, attempt_id, question_id, answer_text, correct)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), attemptId, question.getId(), "something", correct);
    }

    private UUID enrolCard(QuizQuestion question) {
        UUID flashcardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into flashcards (id, course_space_id, created_by, front, back,
                                        source_quiz_question_id)
                values (?, ?, ?, ?, ?, ?)
                """, flashcardId, course.getId(), student.getId(), question.getPrompt(),
                question.getExpectedAnswer(), question.getId());
        jdbcTemplate.update("""
                insert into review_states (flashcard_id, due_on) values (?, current_date)
                """, flashcardId);
        return flashcardId;
    }

    private int attemptCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from quiz_attempts where user_id = ?", Integer.class, student.getId());
        return count == null ? 0 : count;
    }

    private int cardCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from flashcards where created_by = ?", Integer.class, student.getId());
        return count == null ? 0 : count;
    }

    private QuizQuestion saveQuestion(Quiz quiz, int index, String prompt, String expected) {
        QuizQuestion question = new QuizQuestion();
        question.setQuiz(quiz);
        question.setQuestionIndex(index);
        question.setType(QuestionType.SHORT_ANSWER);
        question.setPrompt(prompt);
        question.setExpectedAnswer(expected);
        question.setExplanation("Explained in the paging lecture.");
        return questionRepository.saveAndFlush(question);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Practice " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private CourseSpace saveCourse(User owner, String name) {
        CourseSpace space = new CourseSpace();
        space.setName(name);
        space.setOwner(owner);
        courseSpaceRepository.saveAndFlush(space);

        Membership membership = new Membership();
        membership.setCourseSpace(space);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.saveAndFlush(membership);
        return space;
    }

    private void addMember(User user) {
        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(user);
        membership.setRole(MembershipRole.MEMBER);
        membershipRepository.saveAndFlush(membership);
    }
}
