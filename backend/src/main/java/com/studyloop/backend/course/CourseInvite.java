package com.studyloop.backend.course;

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

// An invitation to join a course. Two flavours share one row:
//   - email == null  → an open share-link: anyone who holds the token can join (reusable).
//   - email != null  → an email invite: only that address may accept, and it's spent on use.
// The granted role (usually MEMBER) is decided when the invite is issued.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_invites")
public class CourseInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    // The opaque secret in the share link; unique so a token maps to exactly one invite.
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    // Null for an open link; otherwise the only address allowed to redeem this invite.
    @Column
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // Null means it never expires.
    @Column(name = "expires_at")
    private Instant expiresAt;

    // Set true once revoked, or once an email invite is spent; such invites no longer redeem.
    @Column(nullable = false)
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}
