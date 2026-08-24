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
    // Embedding a rendered page image, at ingest (Phase 17.1). The same model and the same call as
    // EMBED_DOCUMENTS and kept apart from it for the reason the router's entry is kept apart from
    // the rest of ingestion: it bills in image tokens rather than text tokens, at a different rate,
    // and it scales with how many figures a course uploads rather than with how much it wrote.
    EMBED_IMAGES,
    // Cross-encoder reranking of a retrieved candidate set (Phase 12.1). The only operation billed
    // per search rather than per token, so its rows carry a cost against zero tokens.
    RERANK,
    // Rewriting a question the first retrieval pass answered weakly, and inventing the passage that
    // would have answered it (Phase 18.2). The only chat call on the *retrieval* path rather than
    // the generation path, which is precisely why it gets its own row: it is conditional, so what
    // the dashboard has to be able to answer is "how often did that condition fire" — a cost that
    // scales with how badly the corpus matches how students ask, not with how much they ask.
    QUERY_EXPANSION,
    // Embedding that invented passage (Phase 18.2). Kept apart from EMBED_QUERY because it is
    // embedded as a *document* rather than as a query, and apart from EMBED_DOCUMENTS because it
    // is paid on the request path while a student waits — the same distinction that decides
    // whether a rate-limited embedding call is worth retrying.
    EMBED_HYDE,
    // Generating the questions a section answers, at ingest (Phase 14.1). One call per batch of
    // sections, never on the request path — a student's question does not pay for this.
    SYNTHETIC_QUERIES,
    // Reading a page image with a vision model, at ingest (Phase 15.2). One call per *routed* page,
    // which is the number the whole phase is built to keep small — a separate entry precisely so
    // the dashboard answers "what did the router actually cost" rather than folding it into the
    // rest of ingestion. The only Google row this system writes outside embeddings.
    VLM_EXTRACTION,
    // Reading a photograph of handwritten notes (Phase 16.3). The same provider and the same model
    // as VLM_EXTRACTION, kept apart because they answer different questions about the bill: the
    // router's cost is a property of the *material* a course uploaded and scales with how badly it
    // extracts, while this one is a property of how many students photograph their notebooks. One
    // line cannot go up for both reasons and still be read.
    HANDWRITING_OCR,
    // An answer given from general knowledge after the corpus refused (Phase 20.2). Its own row
    // because it is the one chat call in the product that is *not* grounded, and because the count
    // is a product measurement as much as a cost one: how often the materials sent a student
    // outside them. One call, only ever after an explicit second click.
    GENERAL_KNOWLEDGE,
    // Answering an open forum thread after an upload made it answerable (Phase 20.1). Nobody is
    // waiting on this one at the moment it is billed, which is exactly why it is kept apart: it is
    // the only model call in the system with no user on the other end, so its cost scales with how
    // many threads a course leaves open rather than with what anybody did today.
    FORUM_ANSWER,
    // Writing the narration for a video, scene by scene, from retrieved chunks (Phase 21.2). One
    // call per video, and the only one of the three video rows that scales with how many videos
    // are asked for rather than with how well the other two go.
    VIDEO_SCRIPT,
    // Turning that script into a visual specification per scene — animate this one, put these
    // three lines on a slide for that one (Phase 21.2). Also one call per video, kept apart from
    // the script because they fail differently: a bad script is a bad video, and a bad scene plan
    // is a video of slides.
    VIDEO_SCENE_PLAN,
    // Writing the Manim module for one scene, and rewriting it when the sandbox or the compiler
    // rejects it (Phase 21.3). **The only row here whose count is a quality measurement rather
    // than a usage one.** One call per animated scene means the model wrote working Manim first
    // time; three means the fix loop ran twice and the scene probably still became a slide. The
    // ratio of this row to VIDEO_SCRIPT is the number that decides whether generated animation is
    // worth its cost, and it is the reason the three are not one VIDEO entry.
    VIDEO_SCENE_CODE,
    // A call no scope claimed. A row landing here means an unlabelled call site, not a bug in
    // the caller — it still costs money and still shows up in the total.
    OTHER
}
