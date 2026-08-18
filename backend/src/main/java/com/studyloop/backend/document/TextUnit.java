package com.studyloop.backend.document;

// The smallest piece of text the chunker moves around: a paragraph in tiers 1 and 3, a sentence in
// tier 2, always carrying the page it was printed on.
//
// The page rides along at this level rather than being attached to a finished chunk because a
// chunk's page span is a *consequence* of which units ended up in it, and by the time the chunk
// exists that information is gone.
record TextUnit(String text, int page) { }
