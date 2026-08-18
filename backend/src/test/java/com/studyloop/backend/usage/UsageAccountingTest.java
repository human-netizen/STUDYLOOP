package com.studyloop.backend.usage;

import com.studyloop.backend.config.PricingProperties;
import com.studyloop.backend.config.PricingProperties.ModelPrice;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// The parts of usage accounting that need no database: the pricing arithmetic, the thread-local
// that tells the recorder which feature a call belongs to and whose allowance it comes out of, and
// the filter that opens that scope for a request. Plain JUnit, no Spring context — all of it is
// pure logic (the filter runs against a mock request), and a cached ApplicationContext for them
// would cost a Supabase connection to test something that never touches one.
class UsageAccountingTest {

    private static final PricingProperties PRICING = new PricingProperties(Map.of(
            "command-r-08-2024", ModelPrice.perToken(new BigDecimal("0.15"), new BigDecimal("0.60")),
            "embed-v4.0", ModelPrice.perToken(new BigDecimal("0.12"), BigDecimal.ZERO),
            "rerank-v3.5", ModelPrice.perSearch(new BigDecimal("2.00"))));

    @Test
    void costIsPricedPerMillionTokensAcrossBothDirections() {
        // 1M input at $0.15 plus 0.5M output at $0.60 = $0.15 + $0.30.
        BigDecimal cost = PRICING.priceFor("command-r-08-2024").cost(1_000_000, 500_000);

        assertEquals(new BigDecimal("0.450000"), cost);
    }

    // Six decimal places, matching ai_usage_events.cost_usd. A single small call rounds to
    // something rather than to nothing, which is what keeps a long tail of them visible.
    @Test
    void aSmallCallStillCostsSomething() {
        BigDecimal cost = PRICING.priceFor("command-r-08-2024").cost(1_200, 300);

        assertEquals(new BigDecimal("0.000360"), cost);
    }

    @Test
    void embeddingsAreBilledOnInputOnly() {
        BigDecimal cost = PRICING.priceFor("embed-v4.0").cost(1_000_000, 999);

        assertEquals(new BigDecimal("0.120000"), cost);
    }

    // Reranking is the one call billed per search rather than per token (Phase 12.3): $2.00 per
    // thousand searches is $0.002 a question, and one question is one search however many passages
    // it reranked.
    @Test
    void rerankingIsPricedPerSearchRatherThanPerToken() {
        assertEquals(new BigDecimal("0.002000"),
                PRICING.priceFor("rerank-v3.5").cost(0, 0, 1));
        assertEquals(new BigDecimal("1.000000"),
                PRICING.priceFor("rerank-v3.5").cost(0, 0, 500));
    }

    // A search-priced model reached through the token path costs nothing, and a token-priced model
    // is not charged for searches it never made. The two units stay in their own lanes, which is
    // what lets one ledger row hold either without a column saying which.
    @Test
    void eachModelIsOnlyChargedInTheUnitItBillsIn() {
        assertEquals(new BigDecimal("0.000000"),
                PRICING.priceFor("rerank-v3.5").cost(1_000_000, 1_000_000));
        assertEquals(new BigDecimal("0.150000"),
                PRICING.priceFor("command-r-08-2024").cost(1_000_000, 0, 40));
    }

    // Providers are inconsistent about casing, and a price silently missed over capitalisation
    // would understate the bill without anything looking wrong.
    @Test
    void modelNamesMatchRegardlessOfCase() {
        assertEquals(new BigDecimal("0.150000"),
                PRICING.priceFor("Command-R-08-2024").cost(1_000_000, 0));
    }

    // An unpriced model must not throw: the call already happened and still belongs in the
    // ledger. It shows up as a real call costing nothing, which is a visible prompt to add it.
    @Test
    void anUnknownModelIsFreeRatherThanFatal() {
        assertEquals(new BigDecimal("0.000000"), PRICING.priceFor("some-new-model").cost(50_000, 50_000));
        assertEquals(new BigDecimal("0.000000"), PRICING.priceFor(null).cost(50_000, 50_000));
    }

    @Test
    void aMissingPriceTableBindsToAnEmptyMap() {
        assertSame(Map.of(), new PricingProperties(null).models());
    }

    @Test
    void withNoScopeOpenTheCallersOwnLabelIsUsed() {
        assertEquals(AiOperation.EMBED_QUERY, AiUsageContext.current(AiOperation.EMBED_QUERY));
    }

    @Test
    void anOpenScopeOverridesTheFallback() {
        try (var ignored = AiUsageContext.of(AiOperation.QUIZ_GENERATION)) {
            assertEquals(AiOperation.QUIZ_GENERATION, AiUsageContext.current(AiOperation.OTHER));
        }
        assertEquals(AiOperation.OTHER, AiUsageContext.current(AiOperation.OTHER));
    }

    // The property that keeps a scope from mislabelling calls made after it: closing restores
    // what was in force before, rather than clearing the thread outright.
    @Test
    void closingANestedScopeRestoresTheOuterOne() {
        try (var outer = AiUsageContext.of(AiOperation.SUMMARY)) {
            try (var inner = AiUsageContext.of(AiOperation.EMBED_DOCUMENTS)) {
                assertEquals(AiOperation.EMBED_DOCUMENTS, AiUsageContext.current(AiOperation.OTHER));
            }
            assertEquals(AiOperation.SUMMARY, AiUsageContext.current(AiOperation.OTHER));
        }
        assertEquals(AiOperation.OTHER, AiUsageContext.current(AiOperation.OTHER));
    }

    // ── whose allowance (Phase 10) ──────────────────────────────────────────────────────────

    @Test
    void withNoActorInScopeACallBelongsToNobody() {
        assertNull(AiUsageContext.currentActor());
    }

    // The two axes are set in different places — the actor once per request, the operation several
    // times inside it — so an operation scope opened partway through must not make the call
    // anonymous. This is what keeps quiz generation billed to the student who asked for it.
    @Test
    void labellingTheFeatureKeepsWhoeverIsPayingForIt() {
        UUID student = UUID.randomUUID();

        try (var request = AiUsageContext.actor(student)) {
            try (var work = AiUsageContext.of(AiOperation.QUIZ_GENERATION)) {
                assertEquals(AiOperation.QUIZ_GENERATION, AiUsageContext.current(AiOperation.OTHER));
                assertEquals(student, AiUsageContext.currentActor());
            }
            assertEquals(student, AiUsageContext.currentActor());
        }
        assertNull(AiUsageContext.currentActor());
    }

    // Request threads are pooled and reused. An actor that outlived its request would bill the
    // next person's questions to the previous one.
    @Test
    void closingAnActorScopeRestoresTheOneBeneathIt() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        try (var first = AiUsageContext.actor(outer)) {
            try (var second = AiUsageContext.actor(inner)) {
                assertEquals(inner, AiUsageContext.currentActor());
            }
            assertEquals(outer, AiUsageContext.currentActor());
        }
        assertNull(AiUsageContext.currentActor());
    }

    // The filter that opens that scope for a request, exercised against a mock request rather than
    // a running server — all it does is read the authenticated principal and clean up afterwards,
    // and both halves are worth pinning down.
    @Test
    void theFilterNamesTheAuthenticatedCallerForTheLengthOfTheRequest() throws Exception {
        UUID student = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(student.toString(), null, List.of()));
        UUID[] seenInside = new UUID[1];

        try {
            new AiUsageAttributionFilter().doFilter(
                    new MockHttpServletRequest("POST", "/api/v1/courses/x/chat"),
                    new MockHttpServletResponse(),
                    (req, res) -> seenInside[0] = AiUsageContext.currentActor());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals(student, seenInside[0]);
        assertNull(AiUsageContext.currentActor(), "the scope must not outlive the request");
    }

    // An anonymous request reaches nothing that costs money, but it must not inherit whatever the
    // thread was last used for either.
    @Test
    void anAnonymousRequestIsAttributedToNobody() throws Exception {
        UUID[] seenInside = {UUID.randomUUID()};

        new AiUsageAttributionFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/courses"),
                new MockHttpServletResponse(),
                (req, res) -> seenInside[0] = AiUsageContext.currentActor());

        assertNull(seenInside[0]);
    }
}
