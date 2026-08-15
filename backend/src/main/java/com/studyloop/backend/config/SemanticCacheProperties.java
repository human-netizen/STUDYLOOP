package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Settings for the semantic answer cache (Phase 8.3).
@ConfigurationProperties(prefix = "studyloop.semantic-cache")
public record SemanticCacheProperties(
        // Master switch. Off means every question goes to the model, which is the behaviour
        // before this phase — useful when demonstrating the cost difference.
        boolean enabled,
        // How close a previous question has to be, as cosine similarity, before its answer is
        // reused. This is a high bar on purpose: at 0.97 the two questions are paraphrases, not
        // merely the same topic. Serving a stale answer to a subtly different question is a
        // correctness bug that looks like a working feature, so the threshold errs toward paying.
        double minSimilarity,
        // How long an entry stays eligible. Corpus changes already invalidate a course's entries,
        // so this is the backstop for slow drift rather than the main mechanism.
        Duration ttl
) { }
