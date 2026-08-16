package com.studyloop.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 10 — the two guardrails on the paid surface. They answer different questions and so are
// configured, switched and reported separately:
//
//   rate-limit → "how often", per user. Burst protection. In memory, no database, no history.
//   budget     → "how much", per user. Cost protection. Read from the usage ledger.
//
// One without the other leaves a hole. A rate limit alone still permits a steady drip that costs
// real money all month; a budget alone still permits a script to spend the whole month's
// allowance in ten seconds, which is a load problem before it is a billing one.
@ConfigurationProperties(prefix = "studyloop.quota")
public record QuotaProperties(RateLimit rateLimit, Budget budget) {

    // A token bucket per user per class of endpoint: `capacity` requests may be made at once, and
    // the bucket refills to full over `window`. A bucket rather than a fixed window because the
    // fixed window's edge is exploitable — 20 requests at 11:59:59 and 20 more at 12:00:00 is
    // 40 in a second while never breaking "20 per minute".
    public record RateLimit(
            boolean enabled,
            // Endpoints that call the model or the embedder: chat, search, quizzes, flashcards,
            // summaries. Sized for a person typing, not for a person clicking a button twice.
            int aiRequests,
            Duration aiWindow,
            // Uploads are rated separately and far lower. One PDF is a hundred embedding calls and
            // a summary, so the request count is a poor proxy for what an upload actually costs.
            int uploads,
            Duration uploadWindow
    ) { }

    // Tokens, not requests and not dollars. Requests vary in cost by two orders of magnitude
    // between a one-line question and a quiz over four lectures. Dollars would make the limit
    // depend on the price list, so a provider's price cut would silently loosen everyone's cap.
    public record Budget(
            boolean enabled,
            // Input + output tokens a single user may spend within the window.
            long tokens,
            // Rolling, not calendar: a fixed daily reset gives everyone the same midnight cliff to
            // queue up against, and raises the question of whose midnight.
            Duration window
    ) { }
}
