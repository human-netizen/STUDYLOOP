package com.studyloop.backend.quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// Phase 28.5 — which questions this caller keeps getting wrong.
//
// **Two tables have recorded this on every attempt since Phase 7.2 and Phase 12, and only the due
// queue has ever read either.** `quiz_attempt_answers.correct` is written for every answer of every
// attempt; `review_states.lapses` counts every time a card was failed. Between them they are a
// personalised revision list that the product has never offered.
//
// The union of the two is deliberate rather than tidy. They disagree in both directions: a question
// missed once and never carded is in the first and not the second, and a card failed repeatedly
// long after the quiz is in the second with its original wrong answer long since aged out of
// anybody's memory. Either is a question worth being asked again.
@Repository
@RequiredArgsConstructor
class WrongAnswerRepository {

    private final JdbcTemplate jdbc;

    // The caller's own missed questions in one course, most recently missed first.
    //
    // **Scoped by the attempt's owner and the card's owner, not by the quiz's author**, and that is
    // the whole authorization story: a quiz is shared with the course, so filtering on the quiz
    // would hand back everybody's mistakes. Every branch below is anchored to `?` = the caller.
    //
    // `union` rather than `union all`: a question can be both failed in an attempt and lapsed as a
    // card, and it is one question either way. The outer `group by` then takes the most recent of
    // the two timestamps as its position in the list.
    List<UUID> missedQuestionIds(UUID courseId, UUID actorId, int limit) {
        return jdbc.queryForList("""
                select question_id
                from (
                    select qa.question_id, max(at.created_at) as missed_at
                    from quiz_attempt_answers qa
                    join quiz_attempts at on at.id = qa.attempt_id
                    join quiz_questions qq on qq.id = qa.question_id
                    join quizzes q on q.id = qq.quiz_id
                    where at.user_id = ?
                      and q.course_space_id = ?
                      and qa.correct = false
                    group by qa.question_id

                    union

                    select f.source_quiz_question_id as question_id,
                           max(coalesce(rs.last_reviewed_at, now())) as missed_at
                    from review_states rs
                    join flashcards f on f.id = rs.flashcard_id
                    join quiz_questions qq on qq.id = f.source_quiz_question_id
                    join quizzes q on q.id = qq.quiz_id
                    where f.created_by = ?
                      and q.course_space_id = ?
                      and f.source_quiz_question_id is not null
                      and rs.lapses > 0
                    group by f.source_quiz_question_id
                ) missed
                group by question_id
                order by max(missed_at) desc
                limit ?
                """, UUID.class, actorId, courseId, actorId, courseId, limit);
    }
}
