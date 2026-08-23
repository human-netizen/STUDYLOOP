package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Phase 17.3 — the third ranked list, as a step the pipeline can be run with and without.
//
// **It is a stage rather than three lines in RetrievalService for one reason: the corpus and the
// flag can disagree, and something has to own that.** Visual chunks are written at ingest, so a
// course can hold hundreds of them with this switched off, or none at all with it switched on —
// and the second case is the one that produces a report headed `visual=ON` describing a pipeline
// that never ran. Phase 14 learned that the hard way about synthetic queries. Keeping the switch
// here means the eval can ask the stage what it did rather than asking the configuration what was
// intended.
//
// The stage costs nothing when it is off and almost nothing when it is on: no provider call, no
// second embedding, no extra work at ingest that was not already done. The query vector is the one
// the dense text half was already given, because embed-v4.0 puts a typed question and a picture of
// a page in the same space — which is the whole reason this phase is the cheapest one left in the
// plan.
@Component
@RequiredArgsConstructor
public class VisualStage {

    // The same depth the other two lists use. Deliberately not deeper: RRF rewards agreement
    // between lists, and a visual list twice as long as its neighbours would let a page that only
    // the image search liked outrank a page both text retrievers found.
    private static final int CANDIDATES = 20;

    private final RetrievalProperties properties;
    private final ChunkSearchRepository searchRepository;

    public boolean enabled() {
        return properties.stages().visual();
    }

    // Pages whose picture is near the query, best first. Empty when the stage is off, when no
    // embedding provider ran (so there is no query vector to search with), or when the course has
    // no visual chunks — and all three are the same answer to the caller: one fewer list to fuse.
    public List<ChunkHit> search(UUID courseId, UUID actorId, float[] queryVector) {
        if (!enabled() || queryVector == null) {
            return List.of();
        }
        return searchRepository.visualSearch(
                courseId, actorId, VectorSupport.toLiteral(queryVector), CANDIDATES);
    }
}
