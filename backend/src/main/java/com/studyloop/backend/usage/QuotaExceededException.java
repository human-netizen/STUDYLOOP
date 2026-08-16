package com.studyloop.backend.usage;

import lombok.Getter;

import java.time.Duration;

// A request refused because the caller has had their share, not because anything is wrong with
// the request. Both subclasses answer 429, and both can say when to come back — which is the
// difference between a limit and a wall.
@Getter
public abstract class QuotaExceededException extends RuntimeException {

    // How long until the same request would be allowed. Never zero: a Retry-After of 0 invites an
    // immediate retry that is certain to be refused again.
    private final transient Duration retryAfter;

    protected QuotaExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter.isPositive() ? retryAfter : Duration.ofSeconds(1);
    }

    // Seconds, rounded up, for the Retry-After header — which has no sub-second form, and where
    // rounding down would advertise a moment that is still too early.
    public long retryAfterSeconds() {
        return Math.max(1L, (long) Math.ceil(retryAfter.toMillis() / 1000.0));
    }
}
