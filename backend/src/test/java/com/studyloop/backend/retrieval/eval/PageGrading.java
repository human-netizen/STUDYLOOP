package com.studyloop.backend.retrieval.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// Turns "these chunks came back, in this order" into the gain list RetrievalMetrics scores.
//
// This is the corpus-specific half of the measurement, kept apart from the arithmetic because it
// encodes two judgement calls that the arithmetic must not silently absorb.
public final class PageGrading {

    // A chunk's stored page is where it *starts*, and a chunk can straddle a page boundary, so a
    // fact near the top of page N may live in a chunk that began on N-1. Without this slack the
    // harness would score correct retrievals as misses and every later comparison would inherit
    // the error. Carried over from the Phase 5 harness, which had the same problem.
    public static final int DEFAULT_PAGE_TOLERANCE = 1;

    private PageGrading() {
    }

    // Grade each retrieved chunk, in rank order.
    //
    // The rule that matters: an expected page is credited at most once. Retrieval routinely
    // returns several chunks from the same page, and counting each of them as a fresh hit would
    // let DCG exceed its own ideal — nDCG above 1.0, which is not a score, it is a bug. It would
    // also reward a run that returned six chunks off one page over a run that found all three
    // pages the answer actually spans, which is backwards.
    //
    // `retrievedPages` may contain nulls: a chunk from a forum answer has no page, since it was
    // never in a PDF. Those grade as 0 rather than being dropped, because they occupied a slot in
    // the top-k that a real page could have had, and pretending otherwise would flatter the run.
    public static List<Double> grade(List<Integer> retrievedPages, List<Integer> expectedPages, int tolerance) {
        // Sorted so the nearest-match search below is deterministic when a retrieved page sits
        // within tolerance of two different expected pages.
        TreeSet<Integer> uncredited = new TreeSet<>(expectedPages);
        List<Double> gains = new ArrayList<>(retrievedPages.size());

        for (Integer page : retrievedPages) {
            Integer credited = page == null ? null : claimNearest(uncredited, page, tolerance);
            if (credited != null) {
                uncredited.remove(credited);
                gains.add(1.0);
            } else {
                gains.add(0.0);
            }
        }
        return gains;
    }

    public static List<Double> grade(List<Integer> retrievedPages, List<Integer> expectedPages) {
        return grade(retrievedPages, expectedPages, DEFAULT_PAGE_TOLERANCE);
    }

    // The same rule across a multi-document course, which is what the harness actually grades.
    // Tolerance applies only within a document: page 4 of the hashing chapter is not within one
    // page of page 5 of the sorting chapter, it is a different place entirely.
    public static List<Double> gradeRefs(List<PageRef> retrieved, List<PageRef> expected, int tolerance) {
        List<PageRef> uncredited = new ArrayList<>(expected);
        List<Double> gains = new ArrayList<>(retrieved.size());

        for (PageRef ref : retrieved) {
            PageRef credited = ref == null ? null : claimNearestRef(uncredited, ref, tolerance);
            if (credited != null) {
                uncredited.remove(credited);
                gains.add(1.0);
            } else {
                gains.add(0.0);
            }
        }
        return gains;
    }

    public static List<Double> gradeRefs(List<PageRef> retrieved, List<PageRef> expected) {
        return gradeRefs(retrieved, expected, DEFAULT_PAGE_TOLERANCE);
    }

    // The same rule against chunks that know both of their ends (Phase 13). An expected page is
    // credited when a retrieved chunk *covers* it, rather than when the chunk happens to start
    // within a page of it — so the tolerance above can go to zero and stop crediting retrievals
    // that landed on the neighbouring page and never contained the answer at all.
    //
    // Everything else is unchanged: an expected page is credited at most once, ties go to the lower
    // page, and a null span (a forum chunk with no page, a document this corpus did not seed) still
    // consumes a slot at 0.0 rather than being dropped.
    public static List<Double> gradeSpans(List<PageSpan> retrieved, List<PageRef> expected, int tolerance) {
        List<PageRef> uncredited = new ArrayList<>(expected);
        List<Double> gains = new ArrayList<>(retrieved.size());

        for (PageSpan span : retrieved) {
            PageRef credited = span == null ? null : claimNearestCovered(uncredited, span, tolerance);
            if (credited != null) {
                uncredited.remove(credited);
                gains.add(1.0);
            } else {
                gains.add(0.0);
            }
        }
        return gains;
    }

    private static PageRef claimNearestCovered(List<PageRef> uncredited, PageSpan span, int tolerance) {
        PageRef best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (PageRef candidate : uncredited) {
            if (candidate.document() != span.document()) {
                continue;
            }
            int distance = span.distanceTo(candidate.page());
            if (distance <= tolerance && (distance < bestDistance
                    || (distance == bestDistance && best != null && candidate.page() < best.page()))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static PageRef claimNearestRef(List<PageRef> uncredited, PageRef ref, int tolerance) {
        PageRef best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (PageRef candidate : uncredited) {
            if (candidate.document() != ref.document()) {
                continue;
            }
            int distance = Math.abs(candidate.page() - ref.page());
            // Ties go to the lower page number, so two runs over the same data score the same.
            if (distance <= tolerance && (distance < bestDistance
                    || (distance == bestDistance && best != null && candidate.page() < best.page()))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    // The closest still-uncredited expected page within tolerance, or null. Ties go to the lower
    // page number — arbitrary, but fixed, so two runs over the same data always score the same.
    private static Integer claimNearest(TreeSet<Integer> uncredited, int page, int tolerance) {
        Integer best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Integer candidate : uncredited) {
            int distance = Math.abs(candidate - page);
            if (distance <= tolerance && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }
}
