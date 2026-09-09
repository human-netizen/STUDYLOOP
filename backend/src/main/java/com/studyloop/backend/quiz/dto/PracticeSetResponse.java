package com.studyloop.backend.quiz.dto;

import com.studyloop.backend.quiz.dto.QuizResponse.QuizQuestionView;

import java.util.List;

// Phase 28.5 — the caller's own missed questions, shaped as a quiz to take.
//
// **It is not a `Quiz` and deliberately has no id.** The obvious build — mint a quiz row and copy
// the questions into it — would give every re-served question a *new* id, and the constraint that
// makes this feature safe is `uq_flashcards_owner_quiz_question`: missing the same question twice
// must not mint a second review card. Copies would defeat it silently, one duplicate card per
// practice round. So the original rows are served, and there is no quiz to attach them to.
//
// The consequence is that taking this set records no `quiz_attempts` row — there is no quiz for it
// to belong to. That is the honest shape rather than a limitation to apologise for: an attempt is a
// record of taking *a quiz*, and this is a revision pass over questions whose attempts are already
// recorded on the quizzes they came from.
public record PracticeSetResponse(
        String title,
        // Every question this caller has missed in this course, most recently missed first,
        // capped. Empty is a perfectly good answer and the client says so rather than showing an
        // empty quiz.
        List<QuizQuestionView> questions
) {
}
