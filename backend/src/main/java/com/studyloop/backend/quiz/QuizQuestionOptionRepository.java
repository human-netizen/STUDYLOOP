package com.studyloop.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuizQuestionOptionRepository extends JpaRepository<QuizQuestionOption, UUID> {

    // All options for a set of questions in one query, ordered so callers can group by question
    // without an N+1 per question. Used to assemble the take view and the graded review.
    List<QuizQuestionOption> findByQuestionIdInOrderByQuestionIdAscOptionIndexAsc(List<UUID> questionIds);
}
