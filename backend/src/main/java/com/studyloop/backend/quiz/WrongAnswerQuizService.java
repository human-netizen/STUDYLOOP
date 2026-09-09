package com.studyloop.backend.quiz;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.quiz.dto.PracticeSetResponse;
import com.studyloop.backend.quiz.dto.QuizResponse.QuizQuestionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Phase 28.5 — quiz me on what I keep getting wrong.
//
// **Build (a) of two, and the free one ships first.** This re-serves the questions already missed:
// zero provider calls, and it is what the SM-2 queue would surface anyway, shaped as a quiz instead
// of as a deck. Build (b) — generating *new* questions over the same sections, which tests
// understanding rather than recall of a remembered question — is the existing `QuizService.generate`
// path with a different selection in front of it, and it costs a generation call every time.
//
// Nothing here writes. The selection is a read of the caller's own record; the grading below
// happens in `QuizGradingService`, which owns every judgement about whether an answer is right.
@Service
@RequiredArgsConstructor
public class WrongAnswerQuizService {

    // Twenty is a revision session, not a syllabus. The list is ordered by how recently the
    // question was missed, so a cap takes the freshest mistakes rather than an arbitrary slice.
    private static final int MAX_QUESTIONS = 20;

    private static final String TITLE = "Questions you have missed";

    private final CourseAccess courseAccess;
    private final WrongAnswerRepository wrongAnswerRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizQuestionOptionRepository optionRepository;

    // The caller's own missed questions, in the take view: prompt and choices, never the key.
    @Transactional(readOnly = true)
    public PracticeSetResponse practiceSet(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        List<QuizQuestion> questions = missedQuestions(actorId, courseId);
        if (questions.isEmpty()) {
            return new PracticeSetResponse(TITLE, List.of());
        }

        Map<UUID, List<String>> options = optionsFor(questions);
        List<QuizQuestionView> views = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            QuizQuestion question = questions.get(i);
            // Renumbered from 1 for this set. The question's own `questionIndex` is its position in
            // the quiz it came from, which means nothing here and would render as a set numbered
            // 4, 11, 2.
            views.add(new QuizQuestionView(question.getId(), i + 1, question.getType(),
                    question.getPrompt(), options.getOrDefault(question.getId(), List.of())));
        }
        return new PracticeSetResponse(TITLE, views);
    }

    // The rows behind the set, in the order the repository ranked them. Shared with the grading
    // path so that what is graded is what could have been served — a client cannot submit an answer
    // to a question it was never entitled to see.
    @Transactional(readOnly = true)
    public List<QuizQuestion> missedQuestions(UUID actorId, UUID courseId) {
        List<UUID> ids = wrongAnswerRepository.missedQuestionIds(courseId, actorId, MAX_QUESTIONS);
        if (ids.isEmpty()) {
            return List.of();
        }
        // `findAllById` returns them in whatever order the database chose, so the ranking is
        // reapplied here from the id list, which is the thing that carried it.
        Map<UUID, Integer> rank = new java.util.HashMap<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            rank.put(ids.get(i), i);
        }
        return questionRepository.findAllById(ids).stream()
                .sorted(Comparator.comparingInt(question -> rank.getOrDefault(question.getId(),
                        Integer.MAX_VALUE)))
                .toList();
    }

    private Map<UUID, List<String>> optionsFor(List<QuizQuestion> questions) {
        List<UUID> multipleChoice = questions.stream()
                .filter(question -> question.getType() == QuestionType.MULTIPLE_CHOICE)
                .map(QuizQuestion::getId)
                .toList();
        if (multipleChoice.isEmpty()) {
            return Map.of();
        }
        return optionRepository
                .findByQuestionIdInOrderByQuestionIdAscOptionIndexAsc(multipleChoice).stream()
                .collect(Collectors.groupingBy(
                        option -> option.getQuestion().getId(),
                        Collectors.mapping(QuizQuestionOption::getText, Collectors.toList())));
    }
}
