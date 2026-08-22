package com.studyloop.backend.document;

// Phase 15.3 — the document needed more vision calls than one upload is allowed to make.
//
// **It refuses the document rather than routing the first N pages and stopping.** A 300-page scan
// truncated at page 40 reaches READY holding forty pages of real text and 260 pages of nothing,
// and every symptom of that is silence: the status is green, the page count is right, the chat
// simply cannot answer anything from chapter three onwards. A refused upload is a sentence the
// uploader can read and act on — split the file, or raise the cap.
//
// The cap exists because Phase 10's guardrails protect the *query* path — 20 AI requests a minute,
// 200k tokens a day, per user — and everything from here on multiplies the *ingest* path, which
// has an upload rate limit sized for text extraction and nothing else. One scanned textbook is
// three hundred vision calls inside a single allowed upload.
public class VisionPageCapExceededException extends DocumentExtractionException {

    public VisionPageCapExceededException(int needed, int pageCount, int cap) {
        super(("This document needs the vision extractor on %d of its %d pages, above the "
                + "per-document limit of %d. Split it into smaller files, or raise "
                + "studyloop.vision.max-pages-per-document.").formatted(needed, pageCount, cap));
    }
}
