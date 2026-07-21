package com.studyloop.backend.document;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.course.CourseSpace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

// An uploaded course material and its ingestion state. The (course, sha256) unique
// constraint makes "one copy of a file per course" a database guarantee, so re-uploading
// the same bytes dedupes instead of piling up duplicate chunks/embeddings later.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "documents", uniqueConstraints =
        @UniqueConstraint(name = "uq_document_course_sha256", columnNames = {"course_space_id", "sha256"}))
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    // Original client filename, sanitized to a bare base name.
    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Hex SHA-256 of the file bytes; unique per course so re-uploads dedupe.
    @Column(nullable = false, length = 64)
    private String sha256;

    // Path relative to the storage root where the bytes live ("{courseId}/{sha256}"), so
    // the root can differ between dev and cloud without rewriting rows.
    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    // Filled by the extraction step (Phase 4.3); null until then.
    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    // Populated when status is FAILED — the reason the pipeline stopped.
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Bumped on every status transition, so the client can tell how fresh a status is.
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
