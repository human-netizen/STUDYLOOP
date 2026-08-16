package com.studyloop.backend.analytics;

import com.studyloop.backend.config.AnalyticsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Keeps question clusters warm.
//
// The instructor page recomputes stale clusters itself, so nothing here is load-bearing for
// correctness — this exists so the first person to open the page after a busy day doesn't wait
// for a grouping that could have happened while nobody was looking. Phase 8.1 deliberately
// skipped @Scheduled for the review queue (a `due_on <= today` query has nothing to precompute);
// this is the aggregation that actually earns it.
//
// fixedDelay, not fixedRate: if a run ever takes longer than the interval, the next one waits
// instead of piling up behind it.
@Component
@RequiredArgsConstructor
public class QuestionClusterScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuestionClusterScheduler.class);

    // Only look at courses somebody has asked something in recently. A course whose last question
    // was months ago is already clustered and will fail the staleness check anyway; skipping it
    // here keeps the sweep proportional to activity rather than to the size of the database.
    private static final Duration ACTIVITY_WINDOW = Duration.ofDays(30);

    private final AnalyticsProperties properties;
    private final QuestionEventRepository repository;
    private final QuestionClusteringService clusteringService;
    private final Clock clock;

    @Scheduled(
            initialDelayString = "${studyloop.analytics.cluster-initial-delay:PT2M}",
            fixedDelayString = "${studyloop.analytics.cluster-interval:PT15M}")
    public void refreshStaleClusters() {
        if (!properties.enabled()) {
            return;
        }
        Instant since = clock.instant().minus(ACTIVITY_WINDOW);
        List<UUID> courses = repository.coursesWithQuestions(since);
        int refreshed = 0;

        for (UUID courseId : courses) {
            try {
                // recomputeIfStale is the same call the page makes, and it re-checks staleness
                // itself — so a course clustered thirty seconds ago costs two max() queries here.
                if (clusteringService.recomputeIfStale(courseId)) {
                    refreshed++;
                }
            } catch (RuntimeException e) {
                // One bad course must not stop the sweep; the next tick will try it again.
                log.warn("Could not cluster questions for course {}: {}", courseId, e.getMessage());
            }
        }

        if (refreshed > 0) {
            log.info("Refreshed question clusters for {} of {} active courses", refreshed, courses.size());
        }
    }
}
