package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 25.1 — the value that decides which pages get read.
//
// Small enough to look self-evident, and worth pinning anyway: the one behaviour that cannot be
// seen from a count is that filtering preserves each page's own number, and that is exactly the
// property whose absence would show up months later as citations that open at the wrong page.
class PageRangeTest {

    private static final List<PageText> FOUR_PAGES = List.of(
            new PageText(1, "one"), new PageText(2, "two"),
            new PageText(3, "three"), new PageText(4, "four"));

    @Test
    void filteringKeepsEachPagesOwnNumber() {
        List<PageText> kept = new PageRange(2, 3).filter(FOUR_PAGES);

        assertThat(kept).extracting(PageText::pageNumber).containsExactly(2, 3);
        assertThat(kept).extracting(PageText::text).containsExactly("two", "three");
    }

    @Test
    void anUnboundedRangeReturnsTheSameListUntouched() {
        // The no-cut path is every upload that existed before this phase, and it should allocate
        // nothing and change nothing.
        assertThat(PageRange.all().filter(FOUR_PAGES)).isSameAs(FOUR_PAGES);
        assertThat(PageRange.of(null, null)).isSameAs(PageRange.all());
        assertThat(PageRange.all().isAll()).isTrue();
        assertThat(PageRange.all().describe()).isNull();
    }

    @Test
    void oneOpenSideIsStillARange() {
        assertThat(PageRange.of(3, null).filter(FOUR_PAGES))
                .extracting(PageText::pageNumber).containsExactly(3, 4);
        assertThat(PageRange.of(null, 2).filter(FOUR_PAGES))
                .extracting(PageText::pageNumber).containsExactly(1, 2);
        assertThat(PageRange.of(3, null).describe()).isEqualTo("3-end");
    }

    @Test
    void onlyWhatAClientCanGetWrongWithoutOpeningTheFileIsRefused() {
        assertThatThrownBy(() -> new PageRange(0, 4))
                .isInstanceOf(InvalidPageRangeException.class)
                .hasMessageContaining("1 or greater");
        assertThatThrownBy(() -> new PageRange(9, 3))
                .isInstanceOf(InvalidPageRangeException.class)
                .hasMessageContaining("before the first page");
    }

    @Test
    void aRangeRunningPastTheEndIsAcceptedAndOneStartingPastItIsNot() {
        // "To the end" is a legitimate request from a client whose page count came from a slightly
        // different copy of the file, so the upper bound is not checked here at all. Starting past
        // the end would ingest nothing, which is the silent truncation this codebase refuses.
        PageRange.of(2, 900).requireWithin(4);

        assertThatThrownBy(() -> PageRange.of(9, 12).requireWithin(4))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("4 pages");
    }
}
