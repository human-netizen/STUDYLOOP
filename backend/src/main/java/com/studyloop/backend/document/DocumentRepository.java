package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // Dedup guard: at most one stored copy of a given file per course.
    Optional<Document> findByCourseSpaceIdAndSha256(UUID courseSpaceId, String sha256);

    List<Document> findByCourseSpaceIdOrderByCreatedAtDesc(UUID courseSpaceId);

    // Scopes a lookup to a course, so a document id from another course reads as 404.
    Optional<Document> findByIdAndCourseSpaceId(UUID id, UUID courseSpaceId);
}
