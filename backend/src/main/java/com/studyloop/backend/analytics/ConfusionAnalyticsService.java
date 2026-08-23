package com.studyloop.backend.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.analytics.QuestionEventRepository.LectureHeatRow;
import com.studyloop.backend.analytics.QuestionEventRepository.StoredCluster;
import com.studyloop.backend.analytics.QuestionEventRepository.Totals;
import com.studyloop.backend.analytics.QuestionEventRepository.UngroundedRow;
import com.studyloop.backend.analytics.dto.ConfusionReport;
import com.studyloop.backend.analytics.dto.ConfusionTotals;
import com.studyloop.backend.analytics.dto.LectureHeat;
import com.studyloop.backend.analytics.dto.TopicCluster;
import com.studyloop.backend.analytics.dto.TopicLecture;
import com.studyloop.backend.analytics.dto.UngroundedQuestion;
import com.studyloop.backend.config.AnalyticsProperties;
import com.studyloop.backend.course.CourseAccess;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Assembles the instructor's confusion report (Phase 9.1).
//
// Two aggregates, computed two different ways, and the split is deliberate. Per-lecture heat and
// the ungrounded list are plain GROUP BYs over question_events, so they are always live. Topic
// clustering is the expensive part and reads a cached table — refreshed here first, so "cached"
// never means "stale", only "already paid for".
@Service
@RequiredArgsConstructor
public class ConfusionAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(ConfusionAnalyticsService.class);

    private static final int MIN_WINDOW_DAYS = 1;
    private static final int MAX_WINDOW_DAYS = 365;
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CourseAccess courseAccess;
    private final AnalyticsProperties properties;
    private final QuestionEventRepository repository;
    private final QuestionClusteringService clusteringService;
    private final Clock clock;

    // Instructors and owners only, via requireManager — the same guard that gates uploading
    // documents. A plain MEMBER gets a 403. That is not about secrecy (the data is anonymized
    // either way) but about role: what the class is collectively stuck on is a teaching signal,
    // and showing a student that eleven classmates asked their question changes how they behave.
    @Transactional
    public ConfusionReport report(UUID actorId, UUID courseId, int days) {
        courseAccess.requireManager(actorId, courseId);

        int windowDays = clampWindow(days);
        Instant since = clock.instant().minus(Duration.ofDays(windowDays));

        // Refresh the grouping before reading it, and only if a question has arrived since the
        // last run. On a course nobody has asked anything in, this is two cheap max() queries.
        try {
            clusteringService.recomputeIfStale(courseId);
        } catch (RuntimeException e) {
            // Unlike the write path, a failure here is worth swallowing: the heatmap and the
            // ungrounded list are computed independently and are the more useful half anyway.
            // Better a page missing its topics than a 500 on the instructor's dashboard.
            log.warn("Could not refresh question clusters for course {}: {}", courseId, e.getMessage());
        }

        Totals totals = repository.totals(courseId, since);
        List<LectureHeatRow> heatRows = repository.lectureHeat(courseId, since);
        List<LectureHeat> lectures = toLectureHeat(heatRows);
        Map<UUID, String> filenames = filenamesFrom(heatRows);

        List<TopicCluster> topics = repository.clusters(courseId, properties.maxTopics()).stream()
                .map(cluster -> toTopic(cluster, filenames))
                .toList();

        List<UngroundedQuestion> ungrounded =
                repository.ungrounded(courseId, since, properties.maxUngrounded()).stream()
                        .map(ConfusionAnalyticsService::toUngrounded)
                        .toList();

        return new ConfusionReport(
                windowDays,
                ConfusionTotals.of(totals.asked(), totals.ungrounded(), totals.escalated(),
                        totals.askers()),
                lectures,
                topics,
                ungrounded,
                repository.clustersComputedAt(courseId).orElse(null));
    }

    // Share is computed against the sum of the attributed counts, not against totals.asked():
    // ungrounded questions are counted in the totals but belong to no lecture, so dividing by the
    // course total would make every bar short and none of them add up to anything.
    private static List<LectureHeat> toLectureHeat(List<LectureHeatRow> rows) {
        int attributed = rows.stream().mapToInt(LectureHeatRow::questionCount).sum();
        List<LectureHeat> lectures = new ArrayList<>(rows.size());
        for (LectureHeatRow row : rows) {
            double share = attributed == 0 ? 0.0 : (double) row.questionCount() / attributed;
            lectures.add(new LectureHeat(row.documentId(), row.filename(), row.questionCount(),
                    row.distinctAskers(), share, row.lastAskedAt()));
        }
        return lectures;
    }

    // The heat query already listed every document in the course, so the topic rows get their
    // filenames from it rather than from a second lookup.
    private static Map<UUID, String> filenamesFrom(List<LectureHeatRow> rows) {
        Map<UUID, String> filenames = new HashMap<>();
        for (LectureHeatRow row : rows) {
            filenames.put(row.documentId(), row.filename());
        }
        return filenames;
    }

    private TopicCluster toTopic(StoredCluster cluster, Map<UUID, String> filenames) {
        List<TopicLecture> lectures = readDocumentCounts(cluster.documentCountsJson()).entrySet().stream()
                .map(entry -> new TopicLecture(
                        entry.getKey(),
                        // A document deleted since the last recompute leaves its id behind in the
                        // cached json. Label it rather than dropping the count, which would make
                        // the per-lecture numbers silently fail to add up to questionCount.
                        filenames.getOrDefault(entry.getKey(), "(removed document)"),
                        entry.getValue()))
                .sorted(Comparator.comparingInt(TopicLecture::questionCount).reversed())
                .toList();

        return new TopicCluster(cluster.label(), cluster.questionCount(), cluster.ungroundedCount(),
                cluster.distinctAskers(), lectures, cluster.lastAskedAt());
    }

    private Map<UUID, Integer> readDocumentCounts(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> raw = objectMapper.readValue(json, new TypeReference<>() { });
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            raw.forEach((documentId, count) -> counts.put(UUID.fromString(documentId), count));
            return counts;
        } catch (Exception e) {
            log.warn("Cluster document counts were unreadable: {}", e.getMessage());
            return Map.of();
        }
    }

    private static UngroundedQuestion toUngrounded(UngroundedRow row) {
        return new UngroundedQuestion(row.question(), row.topSimilarity(), row.askedAt(),
                row.eventId(), row.threadId(), row.escalated());
    }

    // A caller asking for 0 days means "the default", not "an empty window"; a caller asking for
    // 10 years means the same as a year. Clamping beats a 400 for a query parameter nobody types
    // by hand.
    private static int clampWindow(int days) {
        if (days <= 0) {
            return DEFAULT_WINDOW_DAYS;
        }
        return Math.min(Math.max(days, MIN_WINDOW_DAYS), MAX_WINDOW_DAYS);
    }
}
