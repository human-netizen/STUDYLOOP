package com.studyloop.backend.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

// Loads fixtures/golden-set.json into typed questions.
public record GoldenSet(int pageTolerance, List<GoldenQuestion> questions) {

    private static final String PATH = "fixtures/golden-set.json";

    public enum Kind {
        // A named quantity — a bound, a threshold, a constant. Lexical search alone often finds
        // these, so they are the kind least able to distinguish one retrieval change from another.
        FACTUAL,
        // Asks for an explanation. Phrased the way a student would, which usually means it shares
        // little vocabulary with the passage that answers it, so it leans on the dense half.
        CONCEPTUAL,
        // Needs two places at once, often in different documents. This is the kind that top-k
        // truncation breaks first: retrieving one of the two pages looks like partial success to a
        // hit-rate and is a wrong answer to a reader.
        SYNTHESIS,
        // The answer lives in a figure caption or a table. Text extraction sees captions but not
        // what they depict, so these are the baseline any visual retrieval work is judged against.
        FIGURE_TABLE,
        // Answerable from nothing in the corpus. Scored only on whether chat refused; never scored
        // on rank, because there is no correct page for it to find.
        UNANSWERABLE
    }

    // `addedIn` is the phase that introduced the question, or 0 for the original set (Phase 17.4).
    //
    // It exists because a golden set that grows quietly stops being a baseline: every average here
    // is over the questions in the file, so four new ones change the denominator of numbers five
    // earlier phases already published. Marked, the report can print both — the original sixty, to
    // be compared against those phases, and all of them, to say what the corpus can now be asked.
    public record GoldenQuestion(String id, Kind kind, String question, List<PageRef> expected,
                                 String note, int addedIn) {

        public boolean answerable() {
            return kind != Kind.UNANSWERABLE;
        }

        // How many distinct places had to be found. This is Recall@k's denominator and nDCG's
        // ideal length, so it is the number that makes a three-page answer score comparably with
        // a one-page answer instead of being penalised for having more to find.
        public int totalRelevant() {
            return expected.size();
        }
    }

    public static GoldenSet load() {
        try {
            JsonNode root = new ObjectMapper().readTree(new ClassPathResource(PATH).getInputStream());
            int tolerance = root.path("pageTolerance").asInt(PageGrading.DEFAULT_PAGE_TOLERANCE);

            List<GoldenQuestion> questions = new ArrayList<>();
            for (JsonNode node : root.get("questions")) {
                questions.add(new GoldenQuestion(
                        node.get("id").asText(),
                        Kind.valueOf(node.get("kind").asText()),
                        node.get("question").asText(),
                        expectedPages(node.get("expected")),
                        node.path("note").asText(""),
                        node.path("addedIn").asInt(0)));
            }
            return new GoldenSet(tolerance, List.copyOf(questions));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + PATH, e);
        }
    }

    // Flattened at load time: the file groups pages under their document because that is readable
    // to write and review, while grading wants one entry per place that has to be found.
    private static List<PageRef> expectedPages(JsonNode expected) {
        List<PageRef> refs = new ArrayList<>();
        for (JsonNode entry : expected) {
            FixtureDocument document = FixtureDocument.valueOf(entry.get("document").asText());
            for (JsonNode page : entry.get("pages")) {
                refs.add(PageRef.of(document, page.asInt()));
            }
        }
        return List.copyOf(refs);
    }

    // The same set with every question misspelled (Phase 18.4). Ids, kinds and expected pages are
    // untouched, so a typo run grades against exactly the same answers and its numbers sit beside a
    // clean run's — the only difference is what was typed.
    //
    // The question text is replaced rather than carried alongside, so the report prints what was
    // actually asked. A report showing the correct spelling of a question the pipeline never saw
    // would be the one artefact of this run capable of misleading somebody reading it later.
    public GoldenSet withTypos() {
        return new GoldenSet(pageTolerance, questions.stream()
                .map(q -> new GoldenQuestion(q.id(), q.kind(), Typos.inject(q.question()),
                        q.expected(), q.note(), q.addedIn()))
                .toList());
    }

    public List<GoldenQuestion> answerable() {
        return questions.stream().filter(GoldenQuestion::answerable).toList();
    }

    public List<GoldenQuestion> unanswerable() {
        return questions.stream().filter(q -> !q.answerable()).toList();
    }

}
