package com.studyloop.backend.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// One section of a guide: its heading, what was written under it, and what that was written from.
//
// **A gap is a section too.** `covered = false` with no body is the output 22.1 exists to make
// possible — the course does not cover this, said in the place a student is looking for it — and
// it is the reason grounding here is mandatory rather than a flag. An ungrounded generator cannot
// produce this row at all: it has no way to know it was about to invent something.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "study_guide_sections")
public class StudyGuideSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guide_id", nullable = false)
    private StudyGuide guide;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String heading;

    // Null exactly when covered is false.
    @Column(columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private boolean covered;

    // Mermaid source, or null. The model writes it in the same call as the body, so it is free
    // beyond its own tokens — and it is text, which is why it can be cited-checked, diffed and
    // printed, none of which is true of a generated image.
    @Column(columnDefinition = "text")
    private String diagram;

    // The Citation list as JSON, numbered from one *within this section*. A snapshot, as on
    // ChatMessage: `@JdbcTypeCode(JSON)` is what binds a String to a jsonb column — a plain String
    // is sent as varchar and Postgres refuses that assignment rather than coercing it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String citations = "[]";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public StudyGuideSection(StudyGuide guide, int position, String heading) {
        this.guide = guide;
        this.position = position;
        this.heading = heading;
    }
}
