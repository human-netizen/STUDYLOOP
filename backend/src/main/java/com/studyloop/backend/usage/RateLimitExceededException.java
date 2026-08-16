package com.studyloop.backend.usage;

import lombok.Getter;

import java.time.Duration;

// Too many requests too quickly. Says nothing about cost — a user well inside their token budget
// can still trip this, and that is the point: it is the guard that acts before any money is spent.
@Getter
public class RateLimitExceededException extends QuotaExceededException {

    private final int limit;
    private final transient Duration window;

    public RateLimitExceededException(Duration retryAfter, int limit, Duration window) {
        super("You're going too fast. Wait a moment and try again.", retryAfter);
        this.limit = limit;
        this.window = window;
    }
}
