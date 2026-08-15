package com.studyloop.backend.usage;

import com.studyloop.backend.config.PricingProperties;
import com.studyloop.backend.config.PricingProperties.ModelPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// The two pieces of usage accounting that need no database: the pricing arithmetic, and the
// thread-local that tells the recorder which feature a call belongs to. Plain JUnit, no Spring
// context — both are pure logic, and a cached ApplicationContext for them would cost a Supabase
// connection to test something that never touches one.
class UsageAccountingTest {

    private static final PricingProperties PRICING = new PricingProperties(Map.of(
            "command-r-08-2024", new ModelPrice(new BigDecimal("0.15"), new BigDecimal("0.60")),
            "embed-v4.0", new ModelPrice(new BigDecimal("0.12"), BigDecimal.ZERO)));

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
}
