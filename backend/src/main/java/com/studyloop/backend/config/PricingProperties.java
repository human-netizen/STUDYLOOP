package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

// What each model costs, keyed by the model name we send to the provider. Configuration rather
// than constants because list prices change and because the dashboard is only as honest as the
// numbers behind it — an operator who negotiated different rates, or who runs a local model for
// free, edits application.yml instead of the code.
//
// Two billing units, because providers use two. Generation and embedding are priced per million
// tokens; reranking is priced per *search* — one call over a batch of passages, whatever their
// length. Phase 12.3 added the second rather than pretending a search is some number of tokens,
// which would have put a made-up token count into the same column the per-user budget sums.
//
// A model with no entry is priced at zero and still recorded: the call count stays true even
// when the money doesn't. That's the right failure mode for a dashboard — a missing price
// understates spend visibly (a column of $0.00 against a real call count) rather than breaking
// the request that made the call.
@ConfigurationProperties(prefix = "studyloop.pricing")
public record PricingProperties(Map<String, ModelPrice> models) {

    private static final ModelPrice FREE =
            new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public PricingProperties {
        models = models == null ? Map.of() : models;
    }

    // Model names are matched case-insensitively; providers are inconsistent about casing, and a
    // price silently missed over capitalisation is worse than having no price table at all.
    public ModelPrice priceFor(String model) {
        if (model == null) {
            return FREE;
        }
        ModelPrice exact = models.get(model);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ModelPrice> entry : models.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(model)) {
                return entry.getValue();
            }
        }
        return FREE;
    }

    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                            BigDecimal perThousandSearches) {

        private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
        private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);

        public ModelPrice {
            inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
            perThousandSearches = perThousandSearches == null ? BigDecimal.ZERO : perThousandSearches;
        }

        // Named constructors, so a price says which unit it is in at the point it is written. Both
        // are static factories rather than extra constructors on purpose: a second constructor on a
        // record leaves Spring's binder with two candidates and no way to pick the canonical one.
        public static ModelPrice perToken(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
            return new ModelPrice(inputPerMillion, outputPerMillion, BigDecimal.ZERO);
        }

        public static ModelPrice perSearch(BigDecimal perThousandSearches) {
            return new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, perThousandSearches);
        }

        public BigDecimal cost(int inputTokens, int outputTokens) {
            return cost(inputTokens, outputTokens, 0);
        }

        // Cost of one call, in whichever units it billed in. The two divisions stay separate
        // because the denominators differ — per million tokens, per thousand searches — and both
        // round to six decimal places, matching ai_usage_events.cost_usd. A model priced in one
        // unit contributes nothing through the other, so summing them is safe rather than clever.
        public BigDecimal cost(int inputTokens, int outputTokens, int searchUnits) {
            BigDecimal in = inputPerMillion.multiply(BigDecimal.valueOf(Math.max(inputTokens, 0)));
            BigDecimal out = outputPerMillion.multiply(BigDecimal.valueOf(Math.max(outputTokens, 0)));
            BigDecimal tokens = in.add(out).divide(MILLION, 6, RoundingMode.HALF_UP);
            BigDecimal searches = perThousandSearches
                    .multiply(BigDecimal.valueOf(Math.max(searchUnits, 0)))
                    .divide(THOUSAND, 6, RoundingMode.HALF_UP);
            return tokens.add(searches);
        }
    }
}
