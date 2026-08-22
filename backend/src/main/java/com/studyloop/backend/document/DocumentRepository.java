package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // Dedup guard: at most one stored copy of a given file per course.
    Optional<Document> findByCourseSpaceIdAndSha256(UUID courseSpaceId, String sha256);

    List<Document> findByCourseSpaceIdOrderByCreatedAtDesc(UUID courseSpaceId);

    // "The material you uploaded", as distinct from the whole corpus: forum-derived documents are
    // retrievable but are not files anyone put here, so they don't belong in a materials list or
    // in a document picker.
    List<Document> findByCourseSpaceIdAndSourceOrderByCreatedAtDesc(UUID courseSpaceId, DocumentSource source);

    // Scopes a lookup to a course, so a document id from another course reads as 404.
    Optional<Document> findByIdAndCourseSpaceId(UUID id, UUID courseSpaceId);

    // The same lookup, plus Phase 16.3's visibility rule: a document is the caller's to read when
    // it is the course's or when they uploaded it.
    //
    // Every feature that takes a *document id from the request* uses this rather than the method
    // above — the summary, quiz generation and flashcard generation all read a named document's
    // chunks, and all three would otherwise read a classmate's private note for anyone who knew
    // its id. Empty rather than a distinct error, because 404 is the honest answer: whether a
    // member has photographed a particular page is itself the thing being kept private.
    @Query("""
            select d from Document d
            where d.id = :id
              and d.courseSpace.id = :courseId
              and (d.visibility = com.studyloop.backend.document.DocumentVisibility.COURSE
                   or d.uploadedBy.id = :actorId)
            """)
    Optional<Document> findVisibleById(@Param("id") UUID id, @Param("courseId") UUID courseId,
                                       @Param("actorId") UUID actorId);

    // The notes one member may see in one course (Phase 16.3): their own, plus every note a
    // manager has promoted to course material. The same OR the retrieval SQL applies to chunks,
    // written once in each of the two places that can leak — a list that showed more than search
    // does would disclose the existence of private notes even without their text.
    @Query("""
            select d from Document d
            where d.courseSpace.id = :courseId
              and d.source = com.studyloop.backend.document.DocumentSource.HANDWRITTEN
              and (d.visibility = com.studyloop.backend.document.DocumentVisibility.COURSE
                   or d.uploadedBy.id = :actorId)
            order by d.createdAt desc
            """)
    List<Document> findVisibleNotes(@Param("courseId") UUID courseId, @Param("actorId") UUID actorId);
}
