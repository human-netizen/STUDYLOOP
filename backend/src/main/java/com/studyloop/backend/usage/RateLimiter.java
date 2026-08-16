package com.studyloop.backend.usage;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// A token bucket per key. Each bucket starts full at `capacity`, one request costs one token, and
// the bucket refills continuously so that an empty one is full again after `window`.
//
// Continuous refill, not a counter reset: it means a caller who has been quiet for half a window
// gets half its allowance back, instead of everyone being released at the same instant. The
// stored value is a double for exactly that reason — a partial token is meaningful state. It is
// also why this isn't a fixed window, whose edge is exploitable: 20 requests at 11:59:59 and 20
// more at 12:00:00 is 40 in one second while never breaking "20 per minute".
//
// In memory, on purpose. A limit is a load control, and putting one in the database makes every
// request pay a round trip to find out whether it may proceed. The cost is that it is per
// instance: two replicas mean two buckets, so the effective limit becomes capacity × replicas.
// That is the standard trade, and the time to change it is when there is a second instance and a
// Redis to hold the counter — neither exists here.
class RateLimiter {

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    // Takes one token if there is one. Returns Duration.ZERO when the request may proceed, and
    // otherwise how long until the bucket has a token again — which is exactly what Retry-After
    // needs, so the caller never has to guess.
    //
    // compute() rather than get-then-put: two threads for the same key must not both read "one
    // token left" and both spend it. ConcurrentHashMap holds the bin's lock for the remapping
    // function, so the read-modify-write is atomic per key.
    Duration take(String key, int capacity, Duration window) {
        if (capacity <= 0) {
            // A capacity of zero means the endpoint is closed, not that it is unlimited.
            return window;
        }
        long now = clock.millis();
        double refillPerMilli = refillPerMilli(capacity, window);
        // Written by the remapping function, read after it returns — compute() runs the function
        // on the calling thread before returning, so there is no visibility gap to worry about.
        double[] tokensLeft = new double[1];

        buckets.compute(key, (ignored, current) -> {
            double tokens = current == null
                    ? capacity
                    : Math.min(capacity, current.tokens() + (now - current.lastRefillAt()) * refillPerMilli);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                tokensLeft[0] = tokens;
            } else {
                // A refused request spends nothing. Charging for it would push a client that
                // retries hard further into debt every time, and it would never be let back in.
                tokensLeft[0] = tokens - 1.0;
            }
            return new Bucket(Math.max(tokens, 0.0), now);
        });

        if (tokensLeft[0] >= 0) {
            return Duration.ZERO;
        }
        // How long until the bucket holds one whole token again.
        double millis = -tokensLeft[0] / refillPerMilli;
        return Duration.ofMillis((long) Math.ceil(millis));
    }

    // Drops buckets that have refilled completely — a full bucket is indistinguishable from one
    // that never existed, so keeping it only leaks memory for every user who ever called.
    // Returns how many went, which is the only thing worth logging about a sweep.
    int prune(int capacity, Duration window) {
        long now = clock.millis();
        double refillPerMilli = refillPerMilli(capacity, window);
        int removed = 0;
        for (Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Bucket bucket = it.next().getValue();
            if (bucket.tokens() + (now - bucket.lastRefillAt()) * refillPerMilli >= capacity) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    int size() {
        return buckets.size();
    }

    // Tokens restored per millisecond, sized so an empty bucket is full again after `window`.
    private static double refillPerMilli(int capacity, Duration window) {
        return (double) capacity / Math.max(window.toMillis(), 1L);
    }

    private record Bucket(double tokens, long lastRefillAt) { }
}
