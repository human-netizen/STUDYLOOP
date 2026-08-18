package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 13 — the knobs on the adaptive chunker.
//
// Deliberately not under `studyloop.retrieval.stages`. Those flags exist so an eval run can be
// reproduced by flipping a property, and chunking cannot work that way: the chunks are already in
// the database when the query arrives, so comparing two chunkers means re-ingesting the corpus,
// not restarting with a different flag. What lives here is the shape of that ingest.
@ConfigurationProperties(prefix = "studyloop.chunking")
public record ChunkingProperties(
        // A ceiling, not a target. It caps a runaway section so one 4,000-token chapter cannot
        // collapse into a single vector that is the average of eight subtopics; nothing is ever
        // padded to reach it and no section is split to make it reach it.
        int maxTokens,
        // Under this, a section is too small to stand alone as a retrieval unit ("4.2.1 Analysis"
        // holding one sentence), so it merges with the sibling that follows it — never across an
        // H1, and never past the ceiling.
        int minTokens,
        // Tier 2: embed sentences and cut where adjacent-sentence similarity drops. Only ever runs
        // for a document with no heading structure at all. Costs one embedding pass over the whole
        // document, which is why it is switchable — a slide deck exported flat is worth it, a
        // 300-page scan with no headings is a bill.
        boolean semantic,
        // Sentences tier 2 will embed before giving up and handing the document to tier 3. A cap
        // rather than a promise: past this the pass costs more than the boundaries are worth.
        int semanticSentenceLimit,
        // Prepend the document title and heading path to the embedded text (13.4). Off means
        // embed_text is left null and both indexes fall back to `content`, which is exactly the
        // pre-13.4 pipeline — so this is the switch that makes the header's effect measurable.
        boolean contextHeader,
        // Small-to-big (13.5): retrieve on chunks, expand each hit to its parent section before
        // the prompt is built. Retrieval precision and generation context are different budgets.
        boolean expandToSection,
        // How much expanded context one source in the prompt may cost. Six sources at 1,200 tokens
        // is a 7,000-token prompt, which is affordable; six unbounded chapters is not.
        int expansionMaxTokens
) {

    private static final int DEFAULT_MAX_TOKENS = 500;
    private static final int DEFAULT_MIN_TOKENS = 120;
    private static final int DEFAULT_SEMANTIC_SENTENCE_LIMIT = 400;
    private static final int DEFAULT_EXPANSION_MAX_TOKENS = 1_200;

    public ChunkingProperties {
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (minTokens < 0) {
            minTokens = DEFAULT_MIN_TOKENS;
        }
        // A floor at or above the ceiling would ask every section to merge with the next one until
        // it broke the ceiling it was merging under — the two constraints would contradict.
        if (minTokens >= maxTokens) {
            minTokens = maxTokens / 4;
        }
        if (semanticSentenceLimit <= 0) {
            semanticSentenceLimit = DEFAULT_SEMANTIC_SENTENCE_LIMIT;
        }
        if (expansionMaxTokens <= 0) {
            expansionMaxTokens = DEFAULT_EXPANSION_MAX_TOKENS;
        }
    }

    public static ChunkingProperties defaults() {
        return new ChunkingProperties(0, DEFAULT_MIN_TOKENS, true, 0, true, true, 0);
    }
}
