package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentSource;

import java.util.UUID;

// A chunk that hybrid retrieval surfaced for a query, plus its fused RRF score (higher = more
// relevant). Carries enough to cite the source (document + page) and to feed the chunk text
// into a RAG prompt (Phase 5.2), so callers never need a second fetch. cosineSimilarity is the
// raw semantic match strength from the vector search (null if the chunk only matched lexically).
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String filename,
        // Uploaded material, or text written back from an accepted forum answer. The client needs
        // it to know whether there is a file to open behind the citation.
        DocumentSource source,
        // 1-based page the chunk starts on; null if the source had no page information.
        Integer pageNumber,
        // 1-based page it runs to (Phase 13). Null for a chunk written before adaptive chunking, or
        // for text that was never on a page. Citations still point at pageNumber — this says how
        // far the passage extends, which is what lets retrieval be graded without a page tolerance.
        Integer pageEnd,
        // The heading trail the chunk sits under, or null. Read by the small-to-big expansion
        // (13.5) to find the rest of the section before the prompt is built.
        String sectionPath,
        String content,
        int tokenCount,
        // TEXT, or VISUAL when this came back from the page-image search (Phase 17.3). The client
        // reads it to mark a citation as pointing at a figure rather than at a passage, and the
        // eval reads it to say how often the third list put something in the top six — the
        // instrument this phase would otherwise be missing, in the same way Phase 14's synthetic
        // queries were invisible until a marker was counted out of the corpus.
        ChunkModality modality,
        double score,
        Double cosineSimilarity,
        // The cross-encoder's calibrated 0..1 relevance for this chunk against this query, or null
        // when the rerank stage did not run (Phase 12.1). Kept beside `score` rather than replacing
        // it because the two are different scales measuring different things — RRF says how well
        // two retrievers agreed on a position, this says how well the passage answers the question
        // — and a report that conflated them could not say which pipeline produced its numbers.
        Double rerankScore
) {

    static RetrievedChunk of(ChunkHit hit, double score) {
        return new RetrievedChunk(
                hit.id(), hit.documentId(), hit.filename(), hit.source(), hit.pageNumber(),
                hit.pageEnd(), hit.sectionPath(), hit.content(), hit.tokenCount(), hit.modality(),
                score, hit.cosineSimilarity(), null);
    }

    RetrievedChunk withRerankScore(double relevance) {
        return new RetrievedChunk(chunkId, documentId, filename, source, pageNumber, pageEnd,
                sectionPath, content, tokenCount, modality, score, cosineSimilarity, relevance);
    }

    public boolean visual() {
        return modality == ChunkModality.VISUAL;
    }
}
