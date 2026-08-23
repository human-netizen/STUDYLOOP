package com.studyloop.backend.document;

// What a chunk's vector was made from (Phase 17.2).
//
// The discriminator exists because one table now holds two kinds of retrievable thing, and the
// difference between them is invisible in every other column: both carry a page number, a
// document, text to put in a prompt and a 768-dimensional vector in the same space. What differs
// is what that vector was computed over, and therefore which query list the row belongs to.
public enum ChunkModality {

    // The vector is an embedding of `embed_text` — the passage plus its context header. Everything
    // written before Phase 17 is this, which is why the column defaults to it.
    TEXT,

    // The vector is an embedding of the *page image*, and `content` is the text that page is
    // answered from. Both halves are needed and for different reasons: embed-v4.0 puts images and
    // text in one space, so a typed question can match a picture — and Command R is text-only, so
    // a chunk retrieved that way still has to arrive at the generator as words.
    VISUAL
}
