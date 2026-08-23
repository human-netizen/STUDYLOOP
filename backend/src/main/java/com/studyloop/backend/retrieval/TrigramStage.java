package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Phase 18.1 — the fuzzy lexical list, as a step the pipeline can be run with and without.
//
// **An LLM is the wrong tool for a typo, and that is the whole argument for this stage existing
// before the two after it.** A misspelled word is a string-distance problem with a fifty-year-old
// answer; `pg_trgm` is in the database already, adds one index, costs no provider call and no
// latency budget, and returns a ranked list the existing fusion takes without modification. The
// alternative — asking a chat model to correct the spelling — puts a network round trip in front
// of every question to fix something Postgres does in a bitmap scan.
//
// What it is *not* is a better lexical retriever for correctly spelled questions. On those it
// mostly re-finds what `plainto_tsquery` already found, which in Reciprocal Rank Fusion means the
// sparse side of the pipeline votes twice. That is a real cost, it is measurable, and it is why
// this arrives behind a flag with an eval run beside it rather than switched on because it sounds
// like an improvement.
@Component
@RequiredArgsConstructor
public class TrigramStage {

    // The same depth the other lists use, for the reason VisualStage gives: RRF rewards agreement
    // between lists, and a list deeper than its neighbours would let a chunk only this retriever
    // liked outrank one two others found.
    private static final int CANDIDATES = 20;

    private final RetrievalProperties properties;
    private final ChunkSearchRepository searchRepository;

    public boolean enabled() {
        return properties.stages().trigram();
    }

    // Chunks containing a near-spelling of one of the query's distinctive words, best first. Empty
    // when the stage is off and when the query has no term long enough to match on — both are the
    // same answer to the caller: one fewer list to fuse.
    public List<ChunkHit> search(UUID courseId, UUID actorId, String query) {
        if (!enabled()) {
            return List.of();
        }
        List<String> terms = QueryTerms.of(query, properties.trigram().maxTerms());
        if (terms.isEmpty()) {
            return List.of();
        }
        return searchRepository.trigramSearch(courseId, actorId, terms, CANDIDATES);
    }
}
