package com.studyloop.backend.usage;

// Which feature a provider call was made for. This is the axis the cost dashboard is actually
// useful along: a single "you spent $2.40" number can't tell you that quiz generation is where
// it went. Stored as the enum name, so entries are safe to add but not to rename.
public enum AiOperation {

    // A grounded chat answer served in one response.
    CHAT,
    // A grounded chat answer streamed token by token. Kept apart from CHAT because the two go
    // through different provider endpoints and only one of them is on the hot path in the UI.
    CHAT_STREAM,
    // Per-document summary + glossary (Phase 8.2).
    SUMMARY,
    QUIZ_GENERATION,
    // Short-answer grading, which asks the model to judge a free-text response.
    QUIZ_GRADING,
    FLASHCARD_GENERATION,
    // Embedding a document's chunks during ingestion — one call per batch, not per document.
    EMBED_DOCUMENTS,
    // Embedding a single question, for retrieval and for the semantic cache probe.
    EMBED_QUERY,
    // Cross-encoder reranking of a retrieved candidate set (Phase 12.1). The only operation billed
    // per search rather than per token, so its rows carry a cost against zero tokens.
    RERANK,
    // Generating the questions a section answers, at ingest (Phase 14.1). One call per batch of
    // sections, never on the request path — a student's question does not pay for this.
    SYNTHETIC_QUERIES,
    // Reading a page image with a vision model, at ingest (Phase 15.2). One call per *routed* page,
    // which is the number the whole phase is built to keep small — a separate entry precisely so
    // the dashboard answers "what did the router actually cost" rather than folding it into the
    // rest of ingestion. The only Google row this system writes outside embeddings.
    VLM_EXTRACTION,
    // A call no scope claimed. A row landing here means an unlabelled call site, not a bug in
    // the caller — it still costs money and still shows up in the total.
    OTHER
}
