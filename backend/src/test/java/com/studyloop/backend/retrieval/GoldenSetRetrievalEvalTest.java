package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 5.4 — retrieval golden-set evaluation. Runs a fixed set of question -> expected-page
// pairs (golden-set.json) through the real hybrid retrieval pipeline against the dev DB and
// measures the page hit-rate: does a chunk from the expected page land in the top-k? This is an
// integration test against live Supabase + Cohere, so it never runs in CI — the system-property
// guard below keeps it dormant unless asked for. Run it deliberately:
//     ./mvnw -Deval.golden=true -Dtest=GoldenSetRetrievalEvalTest test
// The done-when for Phase 5 is >= 10 of 20 questions retrieved with the correct page.
@SpringBootTest
@EnabledIfSystemProperty(named = "eval.golden", matches = "true")
class GoldenSetRetrievalEvalTest {

    private static final int K = 6;
    // A chunk's stored page is where it STARTS, and a chunk can straddle a page boundary, so a
    // fact near the top of page N may live in a chunk that began on N-1. Allow that slack.
    private static final int PAGE_TOLERANCE = 1;
    // The 5.3 confidence gate defaults to 0.30 — every on-topic question should clear it. This
    // run doubles as a sanity check that the threshold isn't cutting into legitimate questions.
    private static final double GATE_THRESHOLD = 0.30;

    @Autowired
    private RetrievalService retrievalService;

    @Test
    void goldenSetPageHitRate() throws Exception {
        JsonNode root = new ObjectMapper().readTree(new ClassPathResource("golden-set.json").getInputStream());
        UUID courseId = UUID.fromString(root.get("courseId").asText());
        UUID actorId = UUID.fromString(root.get("actorId").asText());
        JsonNode questions = root.get("questions");

        int hits = 0;
        int belowGate = 0;
        List<Double> topSimilarities = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n===== Phase 5.4 golden-set retrieval eval =====\n");
        report.append(String.format("course=%s  k=%d  page-tolerance=%d%n%n", courseId, K, PAGE_TOLERANCE));

        for (JsonNode q : questions) {
            String question = q.get("question").asText();
            List<Integer> expected = new ArrayList<>();
            q.get("expectedPages").forEach(p -> expected.add(p.asInt()));

            RetrievalResult result = retrievalService.search(actorId, courseId, question, K);
            List<Integer> retrievedPages = new ArrayList<>();
            for (RetrievedChunk chunk : result.chunks()) {
                if (chunk.pageNumber() != null) {
                    retrievedPages.add(chunk.pageNumber());
                }
            }

            boolean hit = retrievedPages.stream()
                    .anyMatch(page -> expected.stream().anyMatch(e -> Math.abs(page - e) <= PAGE_TOLERANCE));
            if (hit) {
                hits++;
            }

            OptionalDouble topSim = result.topVectorSimilarity();
            topSim.ifPresent(topSimilarities::add);
            if (topSim.isPresent() && topSim.getAsDouble() < GATE_THRESHOLD) {
                belowGate++;
            }

            report.append(String.format("[%s] topSim=%s  expected=%s  retrieved=%s%n        Q: %s%n",
                    hit ? "HIT " : "MISS",
                    topSim.isPresent() ? String.format("%.3f", topSim.getAsDouble()) : "  -  ",
                    expected, retrievedPages, question));
        }

        int total = questions.size();
        double hitRate = (double) hits / total;
        report.append(String.format("%n----- page hit-rate: %d/%d = %.0f%% -----%n", hits, total, hitRate * 100));
        if (!topSimilarities.isEmpty()) {
            double min = topSimilarities.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = topSimilarities.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double avg = topSimilarities.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            report.append(String.format("top cosine similarity — min %.3f / avg %.3f / max %.3f%n", min, avg, max));
            report.append(String.format("questions below the 0.30 gate: %d/%d%n", belowGate, total));
        }
        System.out.println(report);

        // Phase 5 "done when": at least half the golden questions retrieve the right page.
        assertThat(hits)
                .as("golden-set page hit-rate (see printed report above)")
                .isGreaterThanOrEqualTo(total / 2);
    }
}
