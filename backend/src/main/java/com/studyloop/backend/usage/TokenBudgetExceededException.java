package com.studyloop.backend.usage;

import lombok.Getter;

import java.time.Duration;

// The caller has spent their allowance of provider tokens for the window. Unlike a rate limit
// this does not clear in seconds, so the numbers travel with it: a message that says only "come
// back later" is indistinguishable, to the person reading it, from the app being broken.
@Getter
public class TokenBudgetExceededException extends QuotaExceededException {

    private final long usedTokens;
    private final long limitTokens;
    private final transient Duration window;

    public TokenBudgetExceededException(Duration retryAfter, long usedTokens, long limitTokens,
                                        Duration window) {
        super("You've used this period's AI allowance for this account.", retryAfter);
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
        this.window = window;
    }
}
