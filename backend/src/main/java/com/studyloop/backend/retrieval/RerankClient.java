package com.studyloop.backend.retrieval;

import java.util.List;

// Scores a query against a list of passages with a cross-encoder — a model that reads the question
// and one passage together, in the same forward pass, and returns how well that passage answers it.
//
// This is the thing the rest of the pipeline structurally cannot do. RRF fusion reads only each
// chunk's *position* in two ranked lists and never sees the query at all, so a chunk both
// retrievers liked for the wrong reason rises exactly as far as one they liked for the right
// reason. The bi-encoder underneath has the same blind spot one step earlier: question and chunk
// are embedded separately, before either has seen the other, so what gets compared is two
// independent summaries rather than the question against the text.
//
// The price of reading them together is that nothing can be precomputed — every (query, passage)
// pair is its own forward pass — which is why this runs over a few dozen candidates the cheap
// stages already narrowed to, and never over the whole course.
public interface RerankClient {

    boolean isConfigured();

    // Best-first, at most topN entries, each pointing back at its position in `documents`. Fewer
    // than topN is normal; the indices are the provider's and are not checked here.
    List<Ranked> rerank(String query, List<String> documents, int topN);

    // A passage's place in the reranked order. `relevance` is calibrated 0..1: unlike a cosine
    // similarity it means roughly the same thing across queries and corpora, which is the property
    // Phase 12.2's confidence gate needs in order to put a fixed threshold on it.
    record Ranked(int index, double relevance) { }
}
