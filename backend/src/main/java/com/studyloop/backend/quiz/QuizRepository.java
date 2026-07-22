package com.studyloop.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    List<Quiz> findByCourseSpaceIdOrderByCreatedAtDesc(UUID courseSpaceId);

    // Scopes a lookup to a course, so a quiz id from another course reads as 404.
    Optional<Quiz> findByIdAndCourseSpaceId(UUID id, UUID courseSpaceId);
}
