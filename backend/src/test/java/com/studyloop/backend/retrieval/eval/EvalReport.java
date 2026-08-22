package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.retrieval.eval.GoldenSet.GoldenQuestion;
import com.studyloop.backend.retrieval.eval.GoldenSet.Kind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

// Accumulates one run's per-question results and renders the report.
//
// Kept apart from the test because the test is about running the pipeline and this is about what
// the numbers mean, and because the aggregates are read twice: once by the assertions, once by the
// printed report. Two implementations of "the mean of these recalls" would eventually disagree, and
// the one in the report is the one a later phase would quote.
//
// The report states its own configuration — k, tolerance, gate threshold, stage flags, corpus size.
// A retrieval number without them is not comparable to anything: "Recall@6 = 0.71" answers nothing
// unless the next run also had six slots, the same fourteen documents, and the same stages off.
public final class EvalReport {

    private final int k;
    private final int pageTolerance;
    private final double gateThreshold;
    private final double relevanceThreshold;
    private final String stages;
    private final EvalCorpus.Seeded corpus;
    private final List<Row> rows = new ArrayList<>();

    public EvalReport(int k, int pageTolerance, double gateThreshold, double relevanceThreshold,
                      String stages, EvalCorpus.Seeded corpus) {
        this.k = k;
        this.pageTolerance = pageTolerance;
        this.gateThreshold = gateThreshold;
        this.relevanceThreshold = relevanceThreshold;
        this.stages = stages;
        this.corpus = corpus;
    }

    // One question's outcome. `refused` is what the production confidence gate decided about this
    // retrieval — a correct refusal on an unanswerable question and a false refusal on an
    // answerable one, which is why it is recorded for both and averaged separately.
    //
    // `lexicalHits` is the gate's other input. It is carried because the gate is an AND: a question
    // whose words appear anywhere in the corpus is answered regardless of how weak its embedding
    // was, so a similarity threshold on its own does not describe what the gate will do.
    public record Row(
            GoldenQuestion question,
            // The page span of each chunk that came back, in rank order. A span rather than a page
            // since Phase 13: a chunk that runs from page 12 to page 13 covers both, and saying so
            // is what let the ±1 tolerance go.
            List<PageSpan> retrieved,
            double recall,
            double reciprocalRank,
            double ndcg,
            Double topSimilarity,
            // The cross-encoder's relevance for the chunk that came back first, or null when the
            // rerank stage did not run. The gate reads this instead of the cosine when it is
            // present, so the separability section is computed over whichever one applied.
            Double topRelevance,
            int lexicalHits,
            boolean refused
    ) {
    }

    public void add(Row row) {
        rows.add(row);
    }

    // ----- the numbers every later phase is compared against -------------------------------

    public double recall() {
        return mean(answerable().stream().map(Row::recall).toList());
    }

    public double mrr() {
        return mean(answerable().stream().map(Row::reciprocalRank).toList());
    }

    public double ndcg() {
        return mean(answerable().stream().map(Row::ndcg).toList());
    }

    // The fraction of unanswerable questions the gate correctly declined. The regression alarm for
    // Phase 18.2: HyDE invents a plausible passage for a question the corpus cannot answer, which
    // raises its similarity score, and the only symptom is this number falling.
    public double refusalRateOnUnanswerable() {
        List<Row> unanswerable = rows.stream().filter(row -> !row.question().answerable()).toList();
        if (unanswerable.isEmpty()) {
            return 0.0;
        }
        return (double) unanswerable.stream().filter(Row::refused).count() / unanswerable.size();
    }

    // The other half of the same measurement, and the reason refusal rate alone is not a target: a
    // gate that refuses everything scores 1.0 above. This is what that costs.
    public double falseRefusalRate() {
        List<Row> answerable = answerable();
        if (answerable.isEmpty()) {
            return 0.0;
        }
        return (double) answerable.stream().filter(Row::refused).count() / answerable.size();
    }

    public List<Row> misses() {
        return answerable().stream().filter(row -> row.recall() == 0.0).toList();
    }

    public int size() {
        return rows.size();
    }

    // Questions the rerank stage was asked to score and did not: it failed open and they were
    // ranked by RRF instead. Like the check below it this is an instrument reading, not a quality
    // one — a run with some of these is describing two pipelines at once under one heading, and
    // the averages are a blend of both. Cohere's trial key allows ten rerank calls a minute, so an
    // unpaced run of sixty questions produces exactly that.
    public int rerankFallbacks() {
        return (int) rows.stream().filter(row -> row.topRelevance() == null).count();
    }

    // Answerable questions for which no vector search ran. Not a quality measure — a wiring check.
    // Retrieval degrades to full-text alone when the embedding provider is unconfigured, and it
    // does so silently and by design, so without this a run against a missing API key would print
    // a full page of lexical-only numbers under a heading that says hybrid retrieval.
    public int answerableWithoutSemanticSignal() {
        return (int) answerable().stream().filter(row -> row.topSimilarity() == null).count();
    }

    private List<Row> answerable() {
        return rows.stream().filter(row -> row.question().answerable()).toList();
    }

    private List<Row> unanswerable() {
        return rows.stream().filter(row -> !row.question().answerable()).toList();
    }

    // Whether the rerank stage actually produced scores this run. Read off the rows rather than off
    // the stage flag: the flag says what was asked for, and a stage that failed open on every
    // question would still report itself as ON while the numbers below came from the fused order.
    private boolean reranked() {
        return rows.stream().anyMatch(row -> row.topRelevance() != null);
    }

    // ----- rendering ------------------------------------------------------------------------

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("\n===== retrieval eval — golden set v2 =====\n");
        out.append(line("corpus", "%d documents / %d chunks in course %s%s".formatted(
                corpus.documents(), corpus.chunks(), corpus.courseId(),
                corpus.rebuilt() ? "  (ingested %d, reused %d)".formatted(corpus.ingested(), corpus.reused())
                        : "  (all reused)")));
        out.append(line("questions", "%d answerable / %d unanswerable".formatted(
                answerable().size(), rows.size() - answerable().size())));
        out.append(line("k", String.valueOf(k)));
        out.append(line("page tolerance", "±%d".formatted(pageTolerance)));
        // Both thresholds, and which one the gate was reading. A refusal count is meaningless
        // without it: the same 0/8 means "the number is too low" under one rule and "the signal
        // cannot separate them" under the other.
        out.append(line("gate", reranked()
                ? "rerank relevance < %s  (cosine rule not used)".formatted(format(relevanceThreshold))
                : "cosine < %s and no lexical hit".formatted(format(gateThreshold))));
        out.append(line("stages", stages));
        // Printed on every run rather than only when the stage is on, because zero is the reading
        // that matters: it is what a baseline must show, and what a synthetic-queries=ON run that
        // forgot to re-ingest shows as well.
        out.append(line("synthetic queries", "%d/%d chunks carry a generated block".formatted(
                corpus.synthetic(), corpus.chunks())));
        // Phase 15.4, and printed on every run for the same reason as the line above it: zero is
        // the reading that matters. A corpus ingested with no vision key scored every page and sent
        // none, which is not the same pipeline as one where the model read twenty of them, and a
        // report that only mentioned the router when it fired could not tell them apart. Read out
        // of documents.vision_pages rather than off a flag, so it describes the corpus that exists.
        out.append(line("vision extraction", "%d/%d pages read by a vision model".formatted(
                corpus.visionPages(), corpus.pages())));
        if (reranked()) {
            out.append(line("reranked", "%d/%d questions%s".formatted(
                    rows.size() - rerankFallbacks(), rows.size(),
                    rerankFallbacks() == 0 ? ""
                            : "  (%d fell back to the fused order — MIXED RUN)".formatted(rerankFallbacks()))));
        }

        out.append("\n--- headline ---\n");
        out.append(line("Recall@" + k, format(recall())));
        out.append(line("MRR", format(mrr())));
        out.append(line("nDCG@" + k, format(ndcg())));
        out.append(line("refusal on unanswerable", "%s  (%d/%d)".formatted(
                format(refusalRateOnUnanswerable()),
                (int) rows.stream().filter(row -> !row.question().answerable()).filter(Row::refused).count(),
                rows.size() - answerable().size())));
        out.append(line("false refusal on answerable", "%s  (%d/%d)".formatted(
                format(falseRefusalRate()),
                (int) answerable().stream().filter(Row::refused).count(),
                answerable().size())));

        out.append("\n--- by question kind ---\n");
        out.append(String.format(Locale.ROOT, "%-14s %5s %8s %8s %8s%n",
                "kind", "n", "recall", "MRR", "nDCG"));
        Map<Kind, List<Row>> byKind = new EnumMap<>(Kind.class);
        for (Row row : answerable()) {
            byKind.computeIfAbsent(row.question().kind(), kind -> new ArrayList<>()).add(row);
        }
        for (Kind kind : Kind.values()) {
            List<Row> group = byKind.get(kind);
            if (group == null) {
                continue;
            }
            out.append(String.format(Locale.ROOT, "%-14s %5d %8s %8s %8s%n",
                    kind.name().toLowerCase(Locale.ROOT), group.size(),
                    format(mean(group.stream().map(Row::recall).toList())),
                    format(mean(group.stream().map(Row::reciprocalRank).toList())),
                    format(mean(group.stream().map(Row::ndcg).toList()))));
        }

        // The calibration evidence for the gate, and the input Phase 12.2 recalibrated against. If
        // two ranges overlap heavily there is no threshold that separates them, which is the
        // argument for replacing raw cosine with a calibrated rerank score rather than tuning 0.25.
        out.append("\n--- top cosine similarity ---\n");
        out.append(signalLine("answerable", answerable(), Row::topSimilarity));
        out.append(signalLine("unanswerable", unanswerable(), Row::topSimilarity));
        out.append(separability("what a similarity threshold can separate", Row::topSimilarity));
        out.append(line("lexical escape hatch", "%d/%d unanswerable match the corpus lexically"
                .formatted(unanswerable().stream().filter(row -> row.lexicalHits() > 0).count(),
                        unanswerable().size())));

        // The same worked-through comparison on the signal the gate is actually reading. Printed
        // only when something reranked, because an empty section under a heading claiming a
        // calibrated score would read as a result rather than as an absence.
        if (reranked()) {
            out.append("\n--- top rerank relevance ---\n");
            out.append(signalLine("answerable", answerable(), Row::topRelevance));
            out.append(signalLine("unanswerable", unanswerable(), Row::topRelevance));
            out.append(separability("what a relevance threshold can separate", Row::topRelevance));
        }

        out.append("\n--- every question ---\n");
        for (Row row : rows) {
            out.append(renderRow(row));
        }

        List<Row> misses = misses();
        if (!misses.isEmpty()) {
            out.append("\n--- answerable questions that retrieved nothing relevant (%d) ---\n"
                    .formatted(misses.size()));
            for (Row row : misses) {
                out.append("  %s  %s%n".formatted(row.question().id(), row.question().question()));
                out.append("      expected %s%n".formatted(row.question().expected()));
                out.append("      got      %s%n".formatted(row.retrieved()));
                if (!row.question().note().isBlank()) {
                    out.append("      note     %s%n".formatted(row.question().note()));
                }
            }
        }
        return out.toString();
    }

    private String renderRow(Row row) {
        // The relevance column appears only on a run that reranked, so a baseline report keeps the
        // width it had and two reports of the same shape are diffable line for line.
        String relevance = reranked() ? "rel=%-6s ".formatted(format(row.topRelevance())) : "";
        if (!row.question().answerable()) {
            return String.format(Locale.ROOT, "%-4s %-13s %-9s sim=%-6s %s %s%n",
                    row.question().id(), row.question().kind().name().toLowerCase(Locale.ROOT),
                    row.refused() ? "REFUSED" : "ANSWERED", format(row.topSimilarity()), relevance,
                    truncate(row.question().question()));
        }
        return String.format(Locale.ROOT,
                "%-4s %-13s r=%-5s rr=%-5s n=%-5s sim=%-6s %s%s  %s%n",
                row.question().id(), row.question().kind().name().toLowerCase(Locale.ROOT),
                format(row.recall()), format(row.reciprocalRank()), format(row.ndcg()),
                format(row.topSimilarity()), relevance,
                row.refused() ? " GATED" : "      ",
                truncate(row.question().question()));
    }

    // Can *any* threshold on this signal tell the two sets apart? The two ranges above it are
    // summaries; this is the question they exist to answer, worked out rather than left to the eye.
    //
    // Two thresholds bound what a signal can do. The first is the highest one that refuses no
    // answerable question — the free threshold, and everything it catches is pure gain. The second
    // is the one that catches every unanswerable question, and what it costs to get there. When the
    // free threshold catches nothing and the complete one is expensive, the distributions overlap
    // and no amount of tuning fixes it: the signal itself has to change. That is the reading Phase
    // 11.1 got on raw cosine, and the reason 12.2 replaced it rather than retuning 0.25.
    //
    // Both figures describe one comparison in isolation. Under the cosine rule the real gate also
    // requires zero lexical hits, so it refuses strictly less often than the numbers below; the
    // rerank rule has no second condition, so there they are the gate exactly.
    private String separability(String heading, Function<Row, Double> signal) {
        List<Double> answerableScores = values(answerable(), signal);
        List<Double> unanswerableScores = values(unanswerable(), signal);
        if (answerableScores.isEmpty() || unanswerableScores.isEmpty()) {
            return "";
        }

        double free = answerableScores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        long caughtFree = unanswerableScores.stream().filter(score -> score < free).count();
        double complete = unanswerableScores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        long costOfComplete = answerableScores.stream().filter(score -> score <= complete).count();

        StringBuilder out = new StringBuilder("\n--- %s ---%n".formatted(heading));
        out.append(line("free threshold", "%s refuses %d/%d unanswerable at no cost".formatted(
                format(free), caughtFree, unanswerableScores.size())));
        out.append(line("complete threshold", "%s refuses all %d, and %d/%d answerable with them".formatted(
                format(complete), unanswerableScores.size(), costOfComplete, answerableScores.size())));
        return out.toString();
    }

    private static List<Double> values(List<Row> group, Function<Row, Double> signal) {
        return group.stream().map(signal).filter(value -> value != null).toList();
    }

    private String signalLine(String label, List<Row> group, Function<Row, Double> signal) {
        List<Double> scores = values(group, signal);
        if (scores.isEmpty()) {
            return line(label, "not scored on this signal");
        }
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return line(label, "min %s / avg %s / max %s  (n=%d)".formatted(
                format(min), format(mean(scores)), format(max), scores.size()));
    }

    private static String line(String label, String value) {
        return String.format(Locale.ROOT, "%-28s %s%n", label, value);
    }

    private static String truncate(String question) {
        return question.length() <= 72 ? question : question.substring(0, 69) + "...";
    }

    private static String format(Double value) {
        return value == null ? "  -  " : String.format(Locale.ROOT, "%.3f", value);
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
