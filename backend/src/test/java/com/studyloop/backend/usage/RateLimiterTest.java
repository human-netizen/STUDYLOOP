package com.studyloop.backend.usage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The bucket arithmetic on its own — no Spring, no database, and no waiting. Time is a parameter
// here, which is the only reason a "10 requests per minute" rule can be tested in milliseconds.
class RateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final MovableClock clock = new MovableClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void aFreshCallerGetsTheWholeAllowanceBeforeAnythingIsRefused() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.take("alice", 10, MINUTE).isZero(),
                    "request " + (i + 1) + " of 10 should be allowed");
        }

        assertFalse(limiter.take("alice", 10, MINUTE).isZero(), "the eleventh is one too many");
    }

    // A limit that says only "no" leaves the client guessing, and a client that guesses retries in
    // a tight loop. The wait is the interesting half of the answer.
    @Test
    void aRefusalSaysHowLongUntilThereIsRoomAgain() {
        exhaust("alice", 10);

        Duration wait = limiter.take("alice", 10, MINUTE);

        // Ten per minute is one every six seconds, and the bucket was empty.
        assertEquals(6, wait.toSeconds());
    }

    @Test
    void waitingTheStatedTimeBuysExactlyOneMoreRequest() {
        exhaust("alice", 10);
        Duration wait = limiter.take("alice", 10, MINUTE);

        clock.advance(wait);

        assertTrue(limiter.take("alice", 10, MINUTE).isZero(), "the wait it quoted should be enough");
        assertFalse(limiter.take("alice", 10, MINUTE).isZero(), "but it buys one request, not a reset");
    }

    // The property that separates a bucket from a fixed window: the allowance comes back
    // continuously, so a caller who backs off halfway is halfway forgiven.
    @Test
    void theAllowanceRefillsGraduallyRatherThanAllAtOnce() {
        exhaust("alice", 10);

        clock.advance(Duration.ofSeconds(30));

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.take("alice", 10, MINUTE).isZero(),
                    "half a window should return half the allowance");
        }
        assertFalse(limiter.take("alice", 10, MINUTE).isZero(), "and no more than half");
    }

    @Test
    void theBucketNeverFillsBeyondItsCapacity() {
        exhaust("alice", 10);

        // Idle for an hour — a counter that kept accruing would now hand out sixty minutes' worth.
        clock.advance(Duration.ofHours(1));

        exhaust("alice", 10);
        assertFalse(limiter.take("alice", 10, MINUTE).isZero(),
                "a long quiet spell restores the allowance, it does not bank extra");
    }

    // What makes this a per-user limit rather than a global one: one noisy account must not be
    // able to lock everyone else out of the course.
    @Test
    void oneCallerRunningOutDoesNotAffectAnother() {
        exhaust("alice", 10);

        assertTrue(limiter.take("bob", 10, MINUTE).isZero());
    }

    // A refused request costs nothing. If it did, a client retrying in a loop would drive its own
    // bucket further into debt with every attempt and never be readmitted — the failure mode that
    // turns a rate limit into an outage for whoever hits it hardest.
    @Test
    void hammeringWhileRefusedDoesNotPushTheWaitFurtherOut() {
        exhaust("alice", 10);
        Duration first = limiter.take("alice", 10, MINUTE);

        for (int i = 0; i < 50; i++) {
            limiter.take("alice", 10, MINUTE);
        }

        assertEquals(first, limiter.take("alice", 10, MINUTE));
    }

    @Test
    void aCapacityOfZeroClosesTheEndpointRatherThanOpeningIt() {
        assertFalse(limiter.take("alice", 0, MINUTE).isZero());
    }

    // Buckets are created per caller and never removed by the limiter itself, so without the sweep
    // the map grows by one entry for every account that ever makes a request.
    @Test
    void theSweepDropsBucketsThatHaveRefilledAndKeepsThoseStillInUse() {
        exhaust("alice", 10);
        limiter.take("bob", 10, MINUTE);
        assertEquals(2, limiter.size());

        // Long enough for bob's single spent token to be back, not long enough for alice's ten.
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, limiter.prune(10, MINUTE));

        assertEquals(1, limiter.size());
        // And the survivor kept its state rather than being quietly reset by the sweep: ten
        // seconds bought alice one and a bit tokens back, so one request goes through and the
        // next does not. A bucket the sweep had reset would let ten through.
        assertTrue(limiter.take("alice", 10, MINUTE).isZero());
        assertFalse(limiter.take("alice", 10, MINUTE).isZero());
    }

    private void exhaust(String key, int capacity) {
        for (int i = 0; i < capacity; i++) {
            limiter.take(key, capacity, MINUTE);
        }
    }

    // Clock is an abstract class rather than an interface, so a test double is a subclass. Only
    // instant() and the zone accessors are abstract; millis() is derived from instant(), which is
    // the method the limiter actually calls.
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-08-16T09:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }
}
