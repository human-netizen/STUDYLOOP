package com.studyloop.backend.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One billable provider call. Written once and never updated — the cost is the price at the time
// of the call, not whatever the price list says today.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_usage_events")
public class AiUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiOperation operation;

    // Whose allowance this came out of, or null when nobody's — a scheduled sweep has no actor.
    // A plain column rather than a @ManyToOne User: nothing here ever reads the user back, and a
    // mapped association would drag a lazy proxy into a table written from inside other people's
    // transactions.
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    // BigDecimal, not double: these are summed into a money figure, and a run of binary
    // rounding errors across thousands of rows is exactly what numeric(12,6) exists to avoid.
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;
}
