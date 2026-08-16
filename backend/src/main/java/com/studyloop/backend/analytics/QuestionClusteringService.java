package com.studyloop.backend.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.analytics.QuestionEventRepository.ClusterRow;
import com.studyloop.backend.analytics.QuestionEventRepository.EventDocument;
import com.studyloop.backend.analytics.QuestionEventRepository.QuestionVector;
import com.studyloop.backend.config.AnalyticsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Groups a course's questions by meaning, so the instructor page can say "eleven people asked
// about this" instead of listing eleven near-identical strings.
//
// The algorithm is single-pass leader clustering against a running centroid: walk the questions
// oldest-first, drop each into the nearest existing cluster if it clears the threshold, otherwise
// start a new one. That is O(questions × clusters), not O(questions²) — with the window at 500
// and maybe forty topics in a course, it is a few million float operations and finishes in
// milliseconds.
//
// k-means was the obvious alternative and is the wrong tool: it needs k up front, and k is
// precisely the unknown here — nobody can say how many distinct things a class is confused about
// before looking. A similarity threshold expresses the actual requirement ("these two questions
// are about the same thing") and lets the count fall out of the data. The price is order
// sensitivity, which is why the walk is chronological and reproducible rather than arbitrary.
@Service
@RequiredArgsConstructor
public class QuestionClusteringService {

    private static final Logger log = LoggerFactory.getLogger(QuestionClusteringService.class);

    // No time floor on the clustering input: the window is a count, so a quiet course still gets
    // grouped instead of showing nothing because its questions are three weeks old.
    private static final Instant ALL_TIME = Instant.EPOCH;

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so — as
    // elsewhere in this project — we keep our own.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AnalyticsProperties properties;
    private final QuestionEventRepository repository;

    // Recomputes only if a question has arrived since the last run. Both the scheduler and the
    // instructor page call this, which is the point: the page is never stale, and the scheduler
    // means the first instructor to open it after a busy day rarely pays for the grouping.
    @Transactional
    public boolean recomputeIfStale(UUID courseId) {
        Optional<Instant> newestQuestion = repository.newestQuestionAt(courseId);
        if (newestQuestion.isEmpty()) {
            return false;
        }
        Optional<Instant> computedAt = repository.clustersComputedAt(courseId);
        if (computedAt.isPresent() && !newestQuestion.get().isAfter(computedAt.get())) {
            return false;
        }
        recompute(courseId);
        return true;
    }

    @Transactional
    public void recompute(UUID courseId) {
        List<QuestionVector> questions =
                repository.recentVectors(courseId, ALL_TIME, properties.clusterWindow());
        if (questions.isEmpty()) {
            // Still replace: a course whose questions were all deleted should not keep showing
            // the topics they produced.
            repository.replaceClusters(courseId, List.of());
            return;
        }

        Map<UUID, Set<UUID>> documentsByEvent = documentsByEvent(courseId);
        List<Cluster> clusters = cluster(questions);

        List<ClusterRow> rows = clusters.stream()
                .map(cluster -> toRow(cluster, documentsByEvent))
                .sorted(Comparator.comparingInt(ClusterRow::questionCount).reversed())
                .toList();

        repository.replaceClusters(courseId, rows);
        log.debug("Clustered {} questions into {} topics for course {}",
                questions.size(), rows.size(), courseId);
    }

    // ── the algorithm ───────────────────────────────────────────────────────────────────────

    private List<Cluster> cluster(List<QuestionVector> questions) {
        double threshold = properties.clusterThreshold();
        List<Cluster> clusters = new ArrayList<>();

        for (QuestionVector question : questions) {
            Cluster best = null;
            double bestSimilarity = threshold;
            for (Cluster candidate : clusters) {
                double similarity = cosine(question.vector(), candidate.centroid);
                // Strictly greater, so the first cluster to reach the threshold wins ties and the
                // result stays deterministic for a given input order.
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = candidate;
                }
            }
            if (best == null) {
                clusters.add(new Cluster(question));
            } else {
                best.add(question);
            }
        }
        return clusters;
    }

    // A running mean of its members, so the cluster's centre drifts toward whatever the topic
    // actually turns out to be rather than staying pinned to whoever asked first.
    private static final class Cluster {

        private final List<QuestionVector> members = new ArrayList<>();
        private final double[] sum;
        private double[] centroid;

        Cluster(QuestionVector first) {
            this.sum = new double[first.vector().length];
            this.centroid = new double[first.vector().length];
            add(first);
        }

        void add(QuestionVector question) {
            members.add(question);
            float[] vector = question.vector();
            double[] next = new double[sum.length];
            for (int i = 0; i < sum.length && i < vector.length; i++) {
                sum[i] += vector[i];
                next[i] = sum[i] / members.size();
            }
            centroid = next;
        }

        // The real question closest to the centre. A generated topic name would read better, but
        // it costs a provider call per cluster on every recompute, and a student's own wording is
        // the thing an instructor can actually search their inbox for.
        QuestionVector medoid() {
            QuestionVector best = members.get(0);
            double bestSimilarity = -2.0;
            for (QuestionVector member : members) {
                double similarity = cosine(member.vector(), centroid);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = member;
                }
            }
            return best;
        }
    }

    // ── aggregation ─────────────────────────────────────────────────────────────────────────

    private ClusterRow toRow(Cluster cluster, Map<UUID, Set<UUID>> documentsByEvent) {
        int ungrounded = 0;
        Set<UUID> askers = new HashSet<>();
        Map<UUID, Integer> documentCounts = new LinkedHashMap<>();
        Instant lastAsked = Instant.EPOCH;

        for (QuestionVector member : cluster.members) {
            if (!member.grounded()) {
                ungrounded++;
            }
            askers.add(member.askedBy());
            if (member.askedAt().isAfter(lastAsked)) {
                lastAsked = member.askedAt();
            }
            for (UUID documentId : documentsByEvent.getOrDefault(member.id(), Set.of())) {
                documentCounts.merge(documentId, 1, Integer::sum);
            }
        }

        return new ClusterRow(cluster.medoid().question(), cluster.members.size(), ungrounded,
                askers.size(), toJson(documentCounts), lastAsked);
    }

    private Map<UUID, Set<UUID>> documentsByEvent(UUID courseId) {
        Map<UUID, Set<UUID>> byEvent = new HashMap<>();
        for (EventDocument link : repository.documentsFor(courseId, ALL_TIME)) {
            byEvent.computeIfAbsent(link.eventId(), key -> new HashSet<>()).add(link.documentId());
        }
        return byEvent;
    }

    private String toJson(Map<UUID, Integer> documentCounts) {
        try {
            Map<String, Integer> keyed = new LinkedHashMap<>();
            documentCounts.forEach((documentId, count) -> keyed.put(documentId.toString(), count));
            return objectMapper.writeValueAsString(keyed);
        } catch (Exception e) {
            // Losing the per-lecture split costs a column on the page; losing the cluster would
            // cost the row. The lecture heatmap is computed from question_events directly and is
            // unaffected either way.
            log.warn("Could not serialize cluster document counts: {}", e.getMessage());
            return "{}";
        }
    }

    // Full cosine rather than a plain dot product: provider embeddings arrive normalized today,
    // but a centroid of normalized vectors is not itself a unit vector, so the denominator is
    // doing real work here.
    private static double cosine(float[] left, double[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            dot += left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
