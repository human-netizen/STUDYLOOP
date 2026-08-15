package com.studyloop.backend.quiz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.flashcard.FlashcardService;
import com.studyloop.backend.flashcard.FlashcardService.MissedQuestion;
import com.studyloop.backend.quiz.dto.AttemptResponse;
import com.studyloop.backend.quiz.dto.AttemptResponse.GradedAnswer;
import com.studyloop.backend.quiz.dto.AttemptSummaryResponse;
import com.studyloop.backend.quiz.dto.SubmitAttemptRequest;
import com.studyloop.backend.quiz.dto.SubmitAttemptRequest.AnswerInput;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Grades a quiz submission. Multiple-choice is scored deterministically by comparing the picked
// option to the key. Short-answer is judged by the model (same key idea, wording may differ);
// if the provider is unavailable or errors, it falls back to a normalized exact-match so grading
// always completes. The graded attempt is persisted and returned with the answer key + explanations.
@Service
@RequiredArgsConstructor
public class QuizGradingService {

    private static final String GRADER_SYSTEM = """
            You grade short-answer quiz responses. For each item, decide whether the student's \
            answer is essentially correct compared with the expected answer — it counts as correct \
            if it conveys the same key idea, even if the wording differs. Be reasonably lenient on \
            phrasing but strict on meaning.
            Return a SINGLE JSON object, nothing else: {"grades":[{"index":0,"correct":true}]} with \
            exactly one entry per item, using the item's index.
            """;

    // Own ObjectMapper (no bean in this project's modular web stack); tolerate unknown fields.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final QuizService quizService;
    private final QuizQuestionRepository questionRepository;
    private final QuizQuestionOptionRepository optionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuizAttemptAnswerRepository answerRepository;
    private final ChatClient chatClient;
    private final FlashcardService flashcardService;

    @Transactional
    public AttemptResponse submit(UUID actorId, UUID courseId, UUID quizId, SubmitAttemptRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        Quiz quiz = quizService.requireQuiz(courseId, quizId);
        List<QuizQuestion> questions = questionRepository.findByQuizIdOrderByQuestionIndex(quizId);

        Map<UUID, AnswerInput> submitted = indexByQuestion(request.answers());
        Map<UUID, List<String>> optionsByQuestion = loadOptions(questions);
        Map<UUID, Boolean> shortAnswerVerdicts = judgeShortAnswers(questions, submitted);

        // Decide each question's verdict before touching the DB, so grading is a pure step.
        List<Verdict> verdicts = new ArrayList<>(questions.size());
        int score = 0;
        for (QuizQuestion question : questions) {
            AnswerInput answer = submitted.get(question.getId());
            Integer selected = answer != null ? answer.selectedOptionIndex() : null;
            String text = answer != null ? answer.answerText() : null;
            boolean correct = isCorrect(question, selected, text, shortAnswerVerdicts);
            if (correct) {
                score++;
            }
            verdicts.add(new Verdict(question, selected, text, correct));
        }

        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuiz(quiz);
        attempt.setUser(member.getUser());
        attempt.setScore(score);
        attempt.setTotal(questions.size());
        attemptRepository.saveAndFlush(attempt);

        List<GradedAnswer> graded = new ArrayList<>(verdicts.size());
        for (Verdict verdict : verdicts) {
            persistAnswer(attempt, verdict);
            graded.add(toGradedAnswer(verdict, optionsByQuestion));
        }
        answerRepository.flush();

        // Close the loop: what you got wrong becomes a flashcard due today (Phase 8.1).
        int enrolled = flashcardService
                .enrollMissedQuestions(member, missedQuestions(verdicts, optionsByQuestion))
                .size();

        return new AttemptResponse(attempt.getId(), quiz.getId(), score, questions.size(),
                attempt.getCreatedAt(), graded, enrolled);
    }

    // Turns each wrong verdict into card shape: the question on the front, the right answer (plus
    // the explanation, when the generator gave one) on the back.
    private List<MissedQuestion> missedQuestions(List<Verdict> verdicts,
                                                 Map<UUID, List<String>> optionsByQuestion) {
        List<MissedQuestion> missed = new ArrayList<>();
        for (Verdict verdict : verdicts) {
            if (verdict.correct()) {
                continue;
            }
            QuizQuestion question = verdict.question();
            String answer = question.getType() == QuestionType.MULTIPLE_CHOICE
                    ? correctOptionText(question, optionsByQuestion)
                    : question.getExpectedAnswer();
            if (answer == null || answer.isBlank()) {
                continue;
            }
            String back = question.getExplanation() == null || question.getExplanation().isBlank()
                    ? answer
                    : answer + "\n\n" + question.getExplanation();
            missed.add(new MissedQuestion(question.getId(), question.getPrompt(), back));
        }
        return missed;
    }

    private static String correctOptionText(QuizQuestion question, Map<UUID, List<String>> optionsByQuestion) {
        List<String> options = optionsByQuestion.getOrDefault(question.getId(), List.of());
        Integer index = question.getCorrectOptionIndex();
        return index != null && index >= 0 && index < options.size() ? options.get(index) : null;
    }

    @Transactional(readOnly = true)
    public List<AttemptSummaryResponse> listAttempts(UUID actorId, UUID courseId, UUID quizId) {
        courseAccess.requireMember(actorId, courseId);
        quizService.requireQuiz(courseId, quizId);
        return attemptRepository.findByQuizIdAndUserIdOrderByCreatedAtDesc(quizId, actorId).stream()
                .map(attempt -> new AttemptSummaryResponse(
                        attempt.getId(), attempt.getScore(), attempt.getTotal(), attempt.getCreatedAt()))
                .toList();
    }

    // ── grading ─────────────────────────────────────────────────────────────────────────────

    private boolean isCorrect(QuizQuestion question, Integer selected, String text,
                              Map<UUID, Boolean> shortAnswerVerdicts) {
        if (question.getType() == QuestionType.MULTIPLE_CHOICE) {
            return selected != null && selected.equals(question.getCorrectOptionIndex());
        }
        // Short answer: blank is always wrong; otherwise use the judged verdict.
        if (text == null || text.isBlank()) {
            return false;
        }
        return shortAnswerVerdicts.getOrDefault(question.getId(), false);
    }

    // Judges every answered short-answer question in one model call. Returns questionId → correct.
    // Blank answers are excluded (they're wrong without asking). Falls back to normalized equality
    // when the provider is unconfigured or the call/parse fails, so an attempt always grades.
    private Map<UUID, Boolean> judgeShortAnswers(List<QuizQuestion> questions,
                                                 Map<UUID, AnswerInput> submitted) {
        List<ShortAnswerItem> items = new ArrayList<>();
        for (QuizQuestion question : questions) {
            if (question.getType() != QuestionType.SHORT_ANSWER) {
                continue;
            }
            AnswerInput answer = submitted.get(question.getId());
            String text = answer != null ? answer.answerText() : null;
            if (text != null && !text.isBlank()) {
                items.add(new ShortAnswerItem(question.getId(), question.getPrompt(),
                        question.getExpectedAnswer(), text.strip()));
            }
        }
        if (items.isEmpty()) {
            return Map.of();
        }
        if (!chatClient.isConfigured()) {
            return fallbackVerdicts(items);
        }
        try {
            String json;
            // The scope is what separates this call from quiz *generation* in the cost dashboard —
            // both reach the provider through the same completeJson method.
            try (var ignored = AiUsageContext.of(AiOperation.QUIZ_GRADING)) {
                json = chatClient.completeJson(List.of(
                        LlmMessage.system(GRADER_SYSTEM), LlmMessage.user(buildGradingPrompt(items))));
            }
            GradeResult result = objectMapper.readValue(json, GradeResult.class);
            Map<UUID, Boolean> verdicts = new HashMap<>();
            if (result.grades() != null) {
                for (Grade grade : result.grades()) {
                    if (grade.index() != null && grade.index() >= 0 && grade.index() < items.size()) {
                        verdicts.put(items.get(grade.index()).questionId(), Boolean.TRUE.equals(grade.correct()));
                    }
                }
            }
            // Anything the model skipped falls back to exact-match so no answer is left ungraded.
            for (ShortAnswerItem item : items) {
                verdicts.putIfAbsent(item.questionId(), normalizedEquals(item.student(), item.expected()));
            }
            return verdicts;
        } catch (Exception e) {
            return fallbackVerdicts(items);
        }
    }

    private static Map<UUID, Boolean> fallbackVerdicts(List<ShortAnswerItem> items) {
        Map<UUID, Boolean> verdicts = new HashMap<>();
        for (ShortAnswerItem item : items) {
            verdicts.put(item.questionId(), normalizedEquals(item.student(), item.expected()));
        }
        return verdicts;
    }

    private static String buildGradingPrompt(List<ShortAnswerItem> items) {
        StringBuilder prompt = new StringBuilder("Grade these short-answer responses.\n\n");
        for (int i = 0; i < items.size(); i++) {
            ShortAnswerItem item = items.get(i);
            prompt.append("Item ").append(i).append(":\n")
                    .append("Question: ").append(item.prompt()).append('\n')
                    .append("Expected answer: ").append(item.expected() == null ? "" : item.expected()).append('\n')
                    .append("Student answer: ").append(item.student()).append("\n\n");
        }
        return prompt.toString();
    }

    private static boolean normalizedEquals(String a, String b) {
        return normalize(a).equals(normalize(b)) && !normalize(a).isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    // ── persistence + assembly ──────────────────────────────────────────────────────────────

    private void persistAnswer(QuizAttempt attempt, Verdict verdict) {
        QuizAttemptAnswer answer = new QuizAttemptAnswer();
        answer.setAttempt(attempt);
        answer.setQuestion(verdict.question());
        answer.setSelectedOptionIndex(verdict.selected());
        answer.setAnswerText(verdict.text());
        answer.setCorrect(verdict.correct());
        answerRepository.save(answer);
    }

    private GradedAnswer toGradedAnswer(Verdict verdict, Map<UUID, List<String>> optionsByQuestion) {
        QuizQuestion question = verdict.question();
        boolean mc = question.getType() == QuestionType.MULTIPLE_CHOICE;
        return new GradedAnswer(
                question.getId(),
                question.getQuestionIndex(),
                question.getType(),
                question.getPrompt(),
                mc ? optionsByQuestion.getOrDefault(question.getId(), List.of()) : List.of(),
                verdict.selected(),
                verdict.text(),
                verdict.correct(),
                mc ? question.getCorrectOptionIndex() : null,
                mc ? null : question.getExpectedAnswer(),
                question.getExplanation());
    }

    private static Map<UUID, AnswerInput> indexByQuestion(List<AnswerInput> answers) {
        Map<UUID, AnswerInput> byQuestion = new HashMap<>();
        if (answers != null) {
            for (AnswerInput answer : answers) {
                if (answer != null && answer.questionId() != null) {
                    byQuestion.put(answer.questionId(), answer);
                }
            }
        }
        return byQuestion;
    }

    private Map<UUID, List<String>> loadOptions(List<QuizQuestion> questions) {
        List<UUID> mcIds = questions.stream()
                .filter(question -> question.getType() == QuestionType.MULTIPLE_CHOICE)
                .map(QuizQuestion::getId)
                .toList();
        if (mcIds.isEmpty()) {
            return Map.of();
        }
        return optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionIndexAsc(mcIds).stream()
                .collect(Collectors.groupingBy(
                        option -> option.getQuestion().getId(),
                        Collectors.mapping(QuizQuestionOption::getText, Collectors.toList())));
    }

    // A computed verdict for one question, held until the attempt row exists to hang answers off.
    private record Verdict(QuizQuestion question, Integer selected, String text, boolean correct) {
    }

    private record ShortAnswerItem(UUID questionId, String prompt, String expected, String student) {
    }

    // Model output shape for short-answer grading.
    private record GradeResult(List<Grade> grades) {
    }

    private record Grade(Integer index, Boolean correct) {
    }
}
