package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for confusion analytics (Phase 9.1).
@ConfigurationProperties(prefix = "studyloop.analytics")
public record AnalyticsProperties(
        // Master switch. Off means questions are still answered, just not recorded — the
        // comparison to run if anyone asks what the logging costs.
        boolean enabled,
        // How similar two questions must be, as cosine similarity, to land in the same cluster.
        // Much lower than the semantic cache's 0.97, and for the opposite reason: the cache is
        // deciding whether to *reuse an answer*, where a near-miss is a wrong answer, while this
        // is deciding whether two questions are about the same thing. At 0.82 "what is a closure"
        // and "how do closures capture variables" group together, which is the whole point;
        // pushed to 0.95 every phrasing becomes its own topic and the heatmap says nothing.
        double clusterThreshold,
        // Newest N questions per course fed to the clusterer. Bounds the work and keeps the
        // picture current — a topic the class struggled with in week 1 is not today's problem.
        int clusterWindow,
        // Most clusters returned to the UI, largest first.
        int maxTopics,
        // Most individual ungrounded questions listed under the topics.
        int maxUngrounded
) { }
