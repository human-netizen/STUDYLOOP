package com.studyloop.backend.retrieval.eval;

// The pages one retrieved chunk actually covers — where it starts and where it ends.
//
// Before Phase 13 a chunk only knew where it started, because a fixed window started wherever the
// previous one stopped counting and ran on until it stopped counting again. That is the whole
// reason grading needed a ±1 page tolerance: a fact at the top of page 12 could legitimately live
// in a chunk stamped page 11, so the harness had to accept either, and in accepting either it also
// accepted a genuinely wrong retrieval one page away. Adaptive chunks are cut on section
// boundaries and record both ends, so "did this chunk cover the page the answer is on" is now a
// question with an exact answer.
public record PageSpan(FixtureDocument document, int first, int last) {

    public static PageSpan of(FixtureDocument document, int first, Integer last) {
        // A pre-13 chunk, or one from a document with no pages: it covers the page it names.
        return new PageSpan(document, first, last == null ? first : Math.max(first, last));
    }

    // How far outside this span a page sits — zero when the span covers it.
    public int distanceTo(int page) {
        if (page < first) {
            return first - page;
        }
        return page > last ? page - last : 0;
    }

    @Override
    public String toString() {
        return first == last
                ? document.fileName() + ":" + first
                : document.fileName() + ":" + first + "-" + last;
    }
}
