package com.studyloop.backend.forum;

import com.studyloop.backend.auth.User;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

// One reply in a thread. Whether it is *the* answer is the thread's business, not the answer's:
// forum_threads.accepted_answer_id points here, so "accepted" can only ever be true of one reply
// and cannot drift out of step with the thread's status.
//
// Phase 20.1 gave it a second author kind. A reply written by the corpus watch has no `createdBy`
// — see ForumAnswerAuthor for why a sentinel user was the wrong answer to that — and carries the
// two columns that say where it came from: the document whose arrival triggered it, and the
// retrieval confidence behind it.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "forum_answers")
public class ForumAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private ForumThread thread;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_kind", nullable = false, length = 20)
    private ForumAnswerAuthor authorKind = ForumAnswerAuthor.MEMBER;

    // Null exactly when authorKind is ASSISTANT; a database check constraint enforces the pairing
    // rather than trusting every future caller to remember it.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    // The upload that made this thread answerable (20.1). A plain column, not a relationship: it
    // is read to print one filename and never navigated, and a lazy proxy for that is a second
    // query per row on a page that already fetch-joins the authors it does need.
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    // The best question-to-chunk cosine behind the machine's reply, kept so the thread can show
    // the same measurement the refusal recorded. Null on a member's answer, which had no
    // retrieval behind it at all.
    @Column(name = "top_similarity")
    private Double topSimilarity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
