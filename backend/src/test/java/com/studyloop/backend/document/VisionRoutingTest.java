package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.config.VisualProperties;
import com.studyloop.backend.document.TestPdfs.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 15.2 and 15.3 — what the router does with the pages the gate condemned.
//
// The measurable claim (does a vision-read corpus retrieve better) belongs to the eval harness and
// needs a real key and a real scan. What is checkable here, in CI and for nothing, is everything
// the eval cannot see: that only the failing pages cost a call, that a routed page's text is
// actually replaced, that the cost ceiling refuses rather than truncates, and that a document with
// no vision key still ingests.
class VisionRoutingTest {

    private static final String MARKDOWN = "# Chapter 4\n\nThe transcribed page.";

    private final FakeVisionClient vision = new FakeVisionClient();

    private PdfExtractionRouter router() {
        return router(VisionProperties.defaults());
    }

    private PdfExtractionRouter router(VisionProperties properties) {
        // The visual selector is handed an embedder that takes no images, which switches it off —
        // this class is about the vision router, and a selector rendering pages underneath it
        // would add cost and noise to every assertion here. VisualChunkingTest is its opposite.
        VisualPageSelector selector = new VisualPageSelector(
                new PageImageRenderer(), VisualProperties.defaults(), new TextOnlyEmbedder());
        return new PdfExtractionRouter(new PdfTextExtractor(), new PageQualityGate(properties),
                new PageImageRenderer(), vision, properties, selector);
    }

    private static final class TextOnlyEmbedder implements EmbeddingClient {

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static VisionProperties withCap(int cap) {
        return new VisionProperties(true, null, null, 0, cap, null, null, 0, null);
    }

    // ── the ordinary case: nothing to do ────────────────────────────────────────────────────

    @Test
    void aCleanDocumentCostsNoVisionCallAtAll() {
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.PROSE, Kind.BLANK));

        // The whole economic argument of the phase. A digital textbook routes nothing, so the
        // feature is four cheap measurements a page and no provider call — which is what makes it
        // affordable to leave switched on by default.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages()).hasSize(3);
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
    }

    @Test
    void switchingTheRouterOffReproducesThePreviousPipelineExactly() {
        VisionProperties off = new VisionProperties(false, "a-key", null, 0, 0, null, null, 0, null);
        Extraction extraction = router(off).extract(TestPdfs.of(Kind.SCANNED));

        // Not "routes nothing" — scores nothing. A scanned page under an off router is indexed as
        // the empty text PDFBox produced, which is exactly what Phase 14 shipped.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages().get(0).text()).isEmpty();
    }

    // ── routing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aScannedPageIsReplacedByWhatTheModelRead() {
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED, Kind.PROSE));

        assertThat(vision.calls).isEqualTo(1);
        assertThat(extraction.visionPages()).isEqualTo(1);
        // The routed page carries the model's Markdown and its own page number, so a citation to
        // page 2 still opens page 2.
        assertThat(extraction.pages().get(1).pageNumber()).isEqualTo(2);
        assertThat(extraction.pages().get(1).text()).isEqualTo(MARKDOWN);
        // And the pages either side are untouched. A router that rebuilt the whole document from
        // the vision model would be a different, far more expensive feature.
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
        assertThat(extraction.pages().get(2).text()).contains("A skiplist is a sequence");
    }

    @Test
    void onlyTheFailingPagesAreSent() {
        Extraction extraction = router().extract(
                TestPdfs.of(Kind.PROSE, Kind.SCANNED, Kind.PROSE, Kind.FIGURE, Kind.PROSE));

        assertThat(vision.calls).isEqualTo(2);
        assertThat(extraction.visionPages()).isEqualTo(2);
        assertThat(vision.hints).containsExactlyInAnyOrder(PageDefect.SCANNED, PageDefect.FIGURE);
    }

    @Test
    void theDefectTravelsWithThePageSoTheModelIsAskedTheRightThing() {
        router().extract(TestPdfs.of(Kind.TWO_COLUMN));

        // A scanned page needs transcription and a figure page needs a description; sending one
        // prompt for both would waste the one piece of information the gate worked out.
        assertThat(vision.hints).containsExactly(PageDefect.UNRELIABLE_ORDER);
    }

    @Test
    void aBrokenEncodingPageReachesUsableTextThroughTheRouter() {
        // One half of the phase's "done when". The page renders perfectly and extracts as
        // private-use gibberish, so the only route to its content is to look at it.
        Extraction extraction = router().extract(TestPdfs.of(Kind.BROKEN_ENCODING));

        assertThat(extraction.visionPages()).isEqualTo(1);
        assertThat(extraction.pages().get(0).text()).isEqualTo(MARKDOWN);
    }

    @Test
    void everyPageOfAScannedDocumentIsRoutedAndTheDocumentIsWhole() {
        // The other half. Nothing in this document extracted, and all of it comes back.
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED));

        assertThat(extraction.visionPages()).isEqualTo(3);
        assertThat(extraction.pages()).hasSize(3);
        assertThat(extraction.pages()).allSatisfy(page -> assertThat(page.text()).isEqualTo(MARKDOWN));
    }

    // ── no key ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aDeploymentWithNoVisionKeyKeepsIngestingRatherThanFailingEveryUpload() {
        vision.configured = false;
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED));

        // Refusing here would take documents away from an installation that never opted into this
        // phase. The page keeps the text PDFBox produced — which is nothing — and the router logs
        // at WARN, because a corpus indexed this way is quietly worse at being searched and
        // nothing else would ever say so.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages()).hasSize(2);
    }

    // ── 15.3: the cost ceiling ──────────────────────────────────────────────────────────────

    @Test
    void aDocumentNeedingMoreVisionPagesThanTheCapIsRefusedNotTruncated() {
        assertThatThrownBy(() -> router(withCap(2))
                .extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED)))
                .isInstanceOf(VisionPageCapExceededException.class)
                .hasMessageContaining("3 of its 3 pages")
                .hasMessageContaining("limit of 2");

        // Refused *before* spending anything. Routing two pages and then discovering the third is
        // over the line would bill for a document nobody gets.
        assertThat(vision.calls).isZero();
    }

    @Test
    void theCapCountsRoutedPagesRatherThanPages() {
        // A 500-page book with three bad pages is not a 500-page bill, and a cap that counted the
        // document's length would refuse it. What is being bounded is provider calls.
        Extraction extraction = router(withCap(2)).extract(TestPdfs.of(
                Kind.PROSE, Kind.PROSE, Kind.SCANNED, Kind.PROSE, Kind.PROSE, Kind.PROSE));

        assertThat(extraction.visionPages()).isEqualTo(1);
        assertThat(vision.calls).isEqualTo(1);
    }

    // ── failure is loud, but it is about a page or about the run ────────────────────────────
    //
    // **Phase 23.6 (2026-09-11) rewrote the three tests that used to live here**, and the rewrite is
    // the phase. They asserted that any vision failure rejects the whole document — which is what
    // the code did, and what cost a 120-page PDF on 2026-09-07 over one unreadable figure after
    // roughly twenty paid calls. The guarantee worth keeping is not "a failure is fatal"; it is
    // "nothing is ever indexed as better than it is". A degraded page satisfies that by being
    // counted and shown, so a failure about one page should cost one page.
    //
    // What is deliberately *unchanged* and asserted below: the cap still refuses outright, an
    // exhausted daily quota still rejects, and a failed page is still never retried when retrying
    // cannot help.

    @Test
    void aPageTheModelCannotReadCostsThatPageRatherThanTheDocument() {
        // The 2026-09-07 failure, reproduced: `read no text` arrives as HTTP 200 with no content, so
        // it is never retried and used to fall straight through to a throw.
        vision.failByCall.put(1, new VisionExtractionException(
                "Gemini read no text from page 2 (figure)."));

        Extraction extraction = router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED));

        // The document survives, and page 2 keeps whatever PDFBox managed on it.
        assertThat(extraction.pages()).hasSize(2);
        assertThat(extraction.pages().get(1).text()).doesNotContain(MARKDOWN);
        // Counted, which is the only reason degrading is allowed at all.
        assertThat(extraction.degradedPages()).isEqualTo(1);
        // Still billed: visionPages records what the ingest *cost*, degradedPages what it failed to
        // buy. Folding them would make a document that spent a call look like one that did not.
        assertThat(extraction.visionPages()).isEqualTo(1);
    }

    @Test
    void twoPagesFailingDifferentlyBothDegradeAndTheGoodPagesAreUntouched() {
        // 23.6 specifies "one page with a 429 and one with a 400". **The 400 is here and the 429 is
        // deliberately not**, for the reason this file already gives twice: a 429 is retried at
        // 20s, 40s and 60s, so driving one through extract() would be a test that really sleeps for
        // two minutes to re-establish what `aDailyQuotaIsNotWaitedOutAndAPerMinuteOneStillIs` pins
        // directly and instantly. The substitute is the failure the phase actually exists for — an
        // HTTP 200 carrying no usable text — which is also not retried, so the two failing pages
        // still fail *differently* from each other, which is the property under test.
        vision.failByCall.put(1, badRequest("could not decode the image"));
        vision.failByCall.put(2, new VisionExtractionException(
                "Gemini returned no candidate (SAFETY)."));

        Extraction extraction = router()
                .extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED, Kind.PROSE));

        assertThat(extraction.degradedPages()).isEqualTo(2);
        // The third scanned page succeeded, so it carries what the model read — the failures did not
        // poison the pages around them.
        assertThat(extraction.pages().get(2).text()).isEqualTo(MARKDOWN);
        // And the prose page was never sent at all.
        assertThat(extraction.pages().get(3).text()).doesNotContain(MARKDOWN);
    }

    @Test
    void aPageThatCannotBeRetriedIsStillNotRetried() {
        // Unchanged from before 23.6, and still the reason the retry is classified rather than
        // blanket: retrying a malformed request three times at twenty-second intervals turns one
        // lost page into a minute of the executor's time, and other documents are queued behind it.
        // Only the verdict moved — the page degrades now instead of rejecting the document.
        vision.failByCall.put(1, badRequest("bad request"));

        Extraction extraction = router().extract(TestPdfs.of(Kind.SCANNED));

        assertThat(vision.calls).isEqualTo(1);
        assertThat(extraction.degradedPages()).isEqualTo(1);
    }

    @Test
    void anUnusableKeyOrModelRejectsTheDocumentBecauseEveryPageWouldFailTheSameWay() {
        // The line 23.6 draws. A safety block is about one page; a 403 and a 404 are about the
        // deployment, so degrading them would hand back a document whose vision pages were all read
        // by nobody — counted, but useless, and the count would be the only clue. Both are fixed by
        // changing a variable, so the message names the variable rather than the page.
        vision.failWith = new VisionExtractionException("Gemini vision request failed",
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        HttpHeaders.EMPTY, "model not found".getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.SCANNED)))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("VISION_MODEL");

        vision.failWith = new VisionExtractionException("Gemini vision request failed",
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        HttpHeaders.EMPTY, "bad key".getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.SCANNED)))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("GOOGLE_API_KEY");
    }

    private static HttpClientErrorException badRequest(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), null);
    }


    // ── the stub ────────────────────────────────────────────────────────────────────────────

    // Phase 23.4 aftermath — which provider failures are worth trying again, and which are a verdict
    // on the page. These pin the classification directly rather than through extract(), because a
    // rate-limit retry really sleeps for twenty seconds and the thing worth testing is the decision.
    //
    // **The bug these exist for:** until 2026-09-07 retryability was decided by searching the
    // exception *message* for "429", "rate limit" and "quota". On one afternoon a 404 (the pinned
    // Gemini model had been retired) and a 503 (\"high demand … usually temporary\") both fell
    // through it — and because one failed page rejects the whole document, each one cost a
    // 296-page book.
    @Test
    void aRateLimitIsWaitedOutAndAServerErrorIsRetriedSooner() {
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))))
                .isEqualTo(PdfExtractionRouter.Retry.RATE_LIMIT);
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))))
                .isEqualTo(PdfExtractionRouter.Retry.TRANSIENT);
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))))
                .isEqualTo(PdfExtractionRouter.Retry.TRANSIENT);
        // No response at all, and no timeout underneath it: a refused connection, a DNS failure, a
        // dropped socket. Those fail in milliseconds and genuinely do pass on their own, so they
        // keep the short retry. A read timeout carries a SocketTimeoutException and is a different
        // verdict — see aPageThatTakesTooLongIsItsOwnKindOfFailure.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new ResourceAccessException("connection refused"))))
                .isEqualTo(PdfExtractionRouter.Retry.TRANSIENT);
    }

    @Test
    void aRetiredModelIsNotRetried() {
        // The regression guard for 2026-09-07. A 404 is a permanent verdict, and retrying it three
        // times over two minutes per page only makes the same total failure slower.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new HttpClientErrorException(HttpStatus.NOT_FOUND))))
                .isEqualTo(PdfExtractionRouter.Retry.NEVER);
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new HttpClientErrorException(HttpStatus.BAD_REQUEST))))
                .isEqualTo(PdfExtractionRouter.Retry.NEVER);
    }

    @Test
    void aTwoHundredWithNoContentIsNotANetworkProblem() {
        // A safety block and an empty candidate arrive as HTTP 200 and reach here with no HTTP
        // cause at all. Retrying them spends the quota to be told the same thing again.
        assertThat(PdfExtractionRouter.retryKindOf(
                new VisionExtractionException("Gemini returned no candidate for the page (SAFETY)."))) 
                .isEqualTo(PdfExtractionRouter.Retry.NEVER);
    }

    @Test
    void theCauseChainIsWalkedRatherThanTheTopFrame() {
        // The client wraps the HTTP failure before the router ever sees it, so a classifier that
        // tested only the thrown exception would find nothing and call everything permanent.
        RuntimeException nestedTwice = new VisionExtractionException("outer",
                new VisionExtractionException("inner", new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)));
        assertThat(PdfExtractionRouter.retryKindOf(nestedTwice))
                .isEqualTo(PdfExtractionRouter.Retry.RATE_LIMIT);
    }

    // ── 25.3: a page that takes too long, and a quota that cannot pass ──────────────────────

    @Test
    void aPageThatTakesTooLongIsItsOwnKindOfFailure() {
        // The distinction the fallback rests on. Retrying a call that already spent the full
        // timeout is three more timeouts — 45 seconds to re-learn what the first 15 established —
        // so a read timeout is neither retried nor fatal.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")))))
                .isEqualTo(PdfExtractionRouter.Retry.TOO_SLOW);
    }

    @Test
    void aTimeoutIsRecognisedWhicheverExceptionSpringWrappedItIn() {
        // The regression guard for 2026-09-10, and the reason the test above was not enough: it
        // asserted on a shape this code invented rather than one the provider produces. Spring only
        // raises ResourceAccessException when the *request* failed; a timeout that fires while
        // extracting the *response* arrives as a plain RestClientException, which is a subclass of
        // neither branch the classifier tested. A 260-page ingest died on page 120 with `Read timed
        // out` sitting in the chain, unread — one slow page rejecting the whole document, which is
        // the exact outcome 25.3 exists to prevent.
        //
        // Copied from that stack trace, wrapper wording and all.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(new RestClientException(
                "Error while extracting response for type [tools.jackson.databind.JsonNode] "
                        + "and content type [application/octet-stream]",
                new SocketTimeoutException("Read timed out")))))
                .isEqualTo(PdfExtractionRouter.Retry.TOO_SLOW);

        // The other half of the claim, so the fix is "find the timeout" and not "give up quietly on
        // anything unrecognised": a RestClient failure with no timeout under it is still a verdict.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(
                new RestClientException("No HttpMessageConverter for the request"))))
                .isEqualTo(PdfExtractionRouter.Retry.NEVER);

        // And a status code still outranks a socket: the body of a 429 timing out does not make the
        // rate limit stop being a rate limit.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(tooManyRequests(QUOTA_MINUTE_BODY))))
                .isEqualTo(PdfExtractionRouter.Retry.RATE_LIMIT);
    }

    @Test
    void aResponseThatTimesOutWhileBeingReadCostsOnePageRatherThanTheDocument() {
        // The same bug at the level Niloy met it: not a classification, a 260-page upload failing.
        vision.failWith = new VisionExtractionException(
                "Gemini vision request failed: Error while extracting response for type "
                        + "[tools.jackson.databind.JsonNode] and content type [application/octet-stream]",
                new RestClientException("Error while extracting response",
                        new SocketTimeoutException("Read timed out")));

        Extraction extraction = router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED));

        assertThat(extraction.pages()).hasSize(2);
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
        assertThat(extraction.degradedPages()).isEqualTo(1);
        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    void aDailyQuotaIsNotWaitedOutAndAPerMinuteOneStillIs() {
        // The regression guard for 2026-09-08, when a 296-page ingest died on page 289 having
        // already transcribed fourteen pages. Both are HTTP 429 and only the body says which;
        // waiting 20s, then 40s, then 60s cannot make a day pass, and each attempt spends another
        // request against an allowance that is already gone.
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(tooManyRequests(
                QUOTA_DAY_BODY))))
                .isEqualTo(PdfExtractionRouter.Retry.NEVER);
        assertThat(PdfExtractionRouter.retryKindOf(wrapped(tooManyRequests(
                QUOTA_MINUTE_BODY))))
                .isEqualTo(PdfExtractionRouter.Retry.RATE_LIMIT);
    }

    @Test
    void aTimedOutPageKeepsTheTextThatWasAlreadyExtractedAndTheDocumentSurvives() {
        vision.failWith = new VisionExtractionException("Gemini vision request failed",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        Extraction extraction = router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED));

        // The document is whole and usable, the good page is untouched, and the ingest is told
        // exactly what it lost. The first place in this pipeline where a provider failure costs one
        // page instead of the whole upload.
        assertThat(extraction.pages()).hasSize(2);
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
        assertThat(extraction.degradedPages()).isEqualTo(1);
        // Still counted as routed: visionPages records what the ingest cost — the call was made and
        // billed — and degradedPages records what it got back.
        assertThat(extraction.visionPages()).isEqualTo(1);
        // Asked once. The whole point of TOO_SLOW is that it is not retried.
        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    void anExhaustedDailyQuotaFailsTheDocumentNamingTheQuotaRatherThanThePage() {
        vision.failWith = new VisionExtractionException("Gemini vision request failed",
                tooManyRequests(QUOTA_DAY_BODY));

        // It fails rather than falling back, unlike a timeout, and the difference is how many pages
        // are affected: a timeout is one page having a bad minute, an exhausted daily quota is
        // every page after it. Falling back would index a scanned book as blank pages and call it
        // degraded. It is also the one failure here a person can act on, and "could not read page
        // 289" sends them to look at page 289.
        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED)))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("daily free-tier quota")
                .hasMessageContaining("VISION_MODEL")
                .hasMessageNotContaining("page 1");
        assertThat(vision.calls).isEqualTo(1);
    }

    // ── 25.1: the page range ────────────────────────────────────────────────────────────────

    @Test
    void aPageRangeReadsOnlyItsPagesAndKeepsTheirOwnNumbers() {
        Extraction extraction = router().extract(
                TestPdfs.of(Kind.PROSE, Kind.PROSE, Kind.PROSE, Kind.PROSE), new PageRange(2, 3));

        // **The assertion that matters is the numbering, not the count.** A slice renumbered from
        // one would satisfy every count in this file and shift every citation in the document by
        // one page, silently — and the citation viewer opens the whole stored file, so the reader
        // would be sent to a page that does not contain the sentence they clicked.
        assertThat(extraction.pages()).hasSize(2);
        assertThat(extraction.pages()).extracting(PageText::pageNumber).containsExactly(2, 3);
    }

    @Test
    void aPageOutsideTheRangeCostsNoVisionCall() {
        // The economic half of the feature: a cut page is not scored, not rendered and not sent. A
        // scanned appendix at the back of a book whose reader wanted chapter one is the case this
        // exists for.
        Extraction extraction = router().extract(
                TestPdfs.of(Kind.PROSE, Kind.PROSE, Kind.SCANNED), new PageRange(1, 2));

        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages()).hasSize(2);
    }

    @Test
    void aRangePastTheEndIsClampedButOneStartingPastItIsRefused() {
        // "To the end" is a legitimate request from a client whose page count came from a slightly
        // different copy of the file, so the upper bound clamps rather than refusing.
        assertThat(router().extract(TestPdfs.of(Kind.PROSE, Kind.PROSE), new PageRange(2, 900))
                .pages()).hasSize(1);

        // Starting past the end is not: it would ingest nothing and produce a READY document that
        // answers no question about itself, which is the silent truncation this codebase refuses
        // everywhere else.
        assertThatThrownBy(() ->
                router().extract(TestPdfs.of(Kind.PROSE, Kind.PROSE), new PageRange(9, 12)))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("2 pages");
    }

    // Gemini spells the contract into the quota id: per day and per model, which is why naming a
    // different model is a fresh allowance rather than the same exhausted one.
    private static final String QUOTA_DAY_BODY = "{\"error\":{\"details\":[{\"quotaId\":"
                        + "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}";

    private static final String QUOTA_MINUTE_BODY = "{\"error\":{\"details\":[{\"quotaId\":"
                        + "\"GenerateRequestsPerMinutePerProjectPerModel-FreeTier\"}]}}";

    private static HttpClientErrorException tooManyRequests(String body) {
        return (HttpClientErrorException) HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static RuntimeException wrapped(RuntimeException cause) {
        return new VisionExtractionException("The vision extractor could not read page 1 (figure).", cause);
    }

    // Records what it was asked as well as how often. The hints matter: "only the failing pages are
    // sent" and "each is sent with the right instruction" are different claims, and a router that
    // got the second wrong would still pass the first.
    private static final class FakeVisionClient implements VisionClient {

        private volatile boolean configured = true;
        private volatile RuntimeException failWith = null;
        private int calls = 0;
        private final List<PageDefect> hints = new ArrayList<>();
        // Phase 23.6 needs what `failWith` cannot express: *some* pages failing while others
        // succeed, and failing differently from each other. Keyed by call number (1-based) because
        // the client is handed an image and a defect hint, never a page number — the router's own
        // ordering is what maps calls to pages, and a stub that guessed at page numbers would be
        // asserting on its own guess.
        private final Map<Integer, RuntimeException> failByCall = new HashMap<>();

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String readPage(byte[] pngImage, PageDefect hint) {
            calls++;
            hints.add(hint);
            // The renderer is the real one, so this also pins that a page actually rasterised:
            // an empty image would mean PDFBox rendering silently produced nothing.
            assertThat(pngImage).isNotEmpty();
            RuntimeException perCall = failByCall.get(calls);
            if (perCall != null) {
                throw perCall;
            }
            if (failWith != null) {
                throw failWith;
            }
            return MARKDOWN;
        }

        // The PDF router never reads handwriting; HandwrittenNoteReadingTest exercises this half
        // with its own stub. Failing loudly here is deliberate — a router that started calling it
        // would be a routing bug, and a stub that quietly returned something would hide it.
        @Override
        public List<TranscribedBlock> readHandwriting(byte[] image, String mimeType) {
            throw new UnsupportedOperationException(
                    "The PDF router must not read a page as handwriting.");
        }
    }
}
