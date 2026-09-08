package com.studyloop.backend.document;

import java.util.List;

// Which pages of a document the pipeline was asked to read (Phase 25.1).
//
// **The pages it names are the source document's own page numbers, and they stay that way.** A
// range is a filter over a list whose entries already carry their page number — never a slice that
// renumbers from one. That is not a stylistic preference: renumbering shifts every citation in the
// document by `first - 1`, silently, and the citation viewer opens the *whole* stored file, so the
// reader clicking [3] would be sent to a page that does not contain the sentence they clicked. A
// bug of that shape has no symptom at ingest, no symptom in the tests that count chunks, and
// surfaces months later as "the citations are slightly wrong" with nothing to point at.
//
// Both bounds are nullable and mean "no bound on this side", so `all()` is the whole document and
// the record needs no sentinel values. Everything that is not a PDF gets `all()` and never asks.
public record PageRange(Integer first, Integer last) {

    private static final PageRange ALL = new PageRange(null, null);

    // Rejects only what a client can get wrong without ever opening the file. A `last` past the end
    // of the document is *not* wrong here — it is "to the end", and it is checked against the real
    // page count at extraction, where the count is actually known.
    public PageRange {
        if (first != null && first < 1) {
            throw new InvalidPageRangeException("The first page must be 1 or greater.");
        }
        if (last != null && last < 1) {
            throw new InvalidPageRangeException("The last page must be 1 or greater.");
        }
        if (first != null && last != null && last < first) {
            throw new InvalidPageRangeException(
                    "The last page must not be before the first page.");
        }
    }

    public static PageRange all() {
        return ALL;
    }

    // Null-tolerant because it is built from two nullable columns: a document row with no range
    // stored is the whole document, and that is the overwhelmingly common row.
    public static PageRange of(Integer first, Integer last) {
        return first == null && last == null ? ALL : new PageRange(first, last);
    }

    public boolean isAll() {
        return first == null && last == null;
    }

    public boolean contains(int pageNumber) {
        return (first == null || pageNumber >= first) && (last == null || pageNumber <= last);
    }

    // The pages inside the range, with their own numbers untouched. Returns the same list when the
    // range is unbounded, so the no-cut path allocates nothing.
    public List<PageText> filter(List<PageText> pages) {
        if (isAll()) {
            return pages;
        }
        return pages.stream().filter(page -> contains(page.pageNumber())).toList();
    }

    // Checked once, at extraction, against the count only the parsed document knows.
    //
    // A range that starts past the end is a mistake worth refusing: it would otherwise ingest zero
    // pages and produce a READY document that answers nothing, which is the silent-truncation
    // failure this codebase refuses everywhere else. A range that *ends* past the end is not a
    // mistake — it is "to the end" — and clamping it rather than refusing avoids a 400 for a client
    // whose page count came from a different copy of the file.
    public void requireWithin(int pageCount) {
        if (first != null && first > pageCount) {
            throw new DocumentExtractionException(
                    "This document has %d pages, so there is nothing to read from page %d onwards."
                            .formatted(pageCount, first));
        }
    }

    // "12-240", or null when there is nothing to say. Used in log lines and the progress sentence.
    public String describe() {
        if (isAll()) {
            return null;
        }
        return (first == null ? 1 : first) + "-" + (last == null ? "end" : last.toString());
    }
}
