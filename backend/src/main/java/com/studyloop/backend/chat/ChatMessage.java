package com.studyloop.backend.chat;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// One turn in a conversation: who said it, what they said, and — since Phase 28.1 — which
// passages it was shown with.
//
// **The citations were deliberately absent until this phase, and the reason they arrived is that
// the row gained a second reader.** History existed to be replayed to the model, which needs role
// and content; recomputing the sources per answer was therefore free of any obligation to store
// them. A transcript a *person* reopens is a different contract — see V29 for why re-running
// retrieval a week later is not the same answer, and why what was shown is stored as a fact about
// the past rather than derived on demand.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // The Citation list as JSON, exactly as the client was given it. Held as a String rather than
    // as a mapped collection: this is a snapshot, not a relationship — the chunks it names may be
    // gone (27.3) and the row must still read. `@JdbcTypeCode(JSON)` is what makes Hibernate bind
    // it to a jsonb column; a plain String would be sent as varchar and Postgres refuses that
    // assignment rather than coercing it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String citations;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
