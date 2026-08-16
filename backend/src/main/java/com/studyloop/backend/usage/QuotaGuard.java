package com.studyloop.backend.usage;

import com.studyloop.backend.config.QuotaProperties;
import com.studyloop.backend.usage.AiUsageStatsRepository.UserSpend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// The one place that decides whether a user may make an expensive request right now. Both guards
// run, cheapest first: the rate limit is arithmetic on a number already in memory, the budget is
// a query, and there is no point paying for the query when the request is refused either way.
//
// Kept out of the interceptor so the decision can be called from anywhere — a future background
// job that spends on a user's behalf has the same question to ask, and would have no HTTP request
// to hang an interceptor on.
@Service
public class QuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);

    private final QuotaProperties quota;
    private final AiUsageStatsRepository usage;
    private final Clock clock;

    // Two buckets, not one shared limit. An upload is worth roughly a hundred chat questions in
    // provider calls, so counting both against the same allowance would either make uploads
    // effectively unlimited or make chat unusable.
    private final RateLimiter aiLimiter;
    private final RateLimiter uploadLimiter;

    QuotaGuard(QuotaProperties quota, AiUsageStatsRepository usage, Clock clock) {
        this.quota = quota;
        this.usage = usage;
        this.clock = clock;
        this.aiLimiter = new RateLimiter(clock);
        this.uploadLimiter = new RateLimiter(clock);
    }

    // A request that will call the model or the embedder: chat, search, quizzes, flashcards,
    // summaries, accepting a forum answer.
    public void checkAi(UUID userId) {
        if (userId == null) {
            return;
        }
        QuotaProperties.RateLimit limit = quota.rateLimit();
        rateLimit(aiLimiter, userId, limit.aiRequests(), limit.aiWindow());
        checkBudget(userId);
    }

    // An upload, which is not one provider call but as many as the document has chunks, plus a
    // summary. Rated on its own far lower allowance; the budget applies to it just the same.
    public void checkUpload(UUID userId) {
        if (userId == null) {
            return;
        }
        QuotaProperties.RateLimit limit = quota.rateLimit();
        rateLimit(uploadLimiter, userId, limit.uploads(), limit.uploadWindow());
        checkBudget(userId);
    }

    private void rateLimit(RateLimiter limiter, UUID userId, int capacity, Duration window) {
        if (!quota.rateLimit().enabled()) {
            return;
        }
        Duration wait = limiter.take(userId.toString(), capacity, window);
        if (!wait.isZero()) {
            throw new RateLimitExceededException(wait, capacity, window);
        }
    }

    // Deliberately a check on what has already been spent, not a reservation of what is about to
    // be. Nobody can know what a request will cost before making it — the answer's length is the
    // model's decision — so the honest guarantee is "you cannot start another expensive request
    // once you are over", which lets a single call overshoot the limit by its own size. Reserving
    // an estimate instead would refuse requests that would have fit, and still be wrong.
    private void checkBudget(UUID userId) {
        QuotaProperties.Budget budget = quota.budget();
        if (!budget.enabled() || budget.tokens() <= 0) {
            return;
        }
        Instant now = clock.instant();
        UserSpend spend = usage.spendSince(userId, now.minus(budget.window()));
        if (spend.tokens() < budget.tokens()) {
            return;
        }
        // The window rolls, so headroom returns when the oldest call still inside it ages out.
        Duration retryAfter = spend.oldest() == null
                ? budget.window()
                : Duration.between(now, spend.oldest().plus(budget.window()));
        throw new TokenBudgetExceededException(retryAfter, spend.tokens(), budget.tokens(),
                budget.window());
    }

    // Buckets are created per user on first use and never removed by the limiter itself, so
    // without this the map grows by one entry for every account that ever makes a request. A
    // bucket that has refilled to capacity holds no information — dropping it is indistinguishable
    // from keeping it, except in memory.
    @Scheduled(fixedDelayString = "${studyloop.quota.rate-limit.prune-interval:PT10M}")
    void pruneIdleBuckets() {
        QuotaProperties.RateLimit limit = quota.rateLimit();
        int dropped = aiLimiter.prune(limit.aiRequests(), limit.aiWindow())
                + uploadLimiter.prune(limit.uploads(), limit.uploadWindow());
        if (dropped > 0) {
            log.debug("Pruned {} idle rate-limit buckets", dropped);
        }
    }
}
