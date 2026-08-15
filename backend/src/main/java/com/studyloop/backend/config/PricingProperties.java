package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

// What each model costs, in USD per million tokens, keyed by the model name we send to the
// provider. Configuration rather than constants because list prices change and because the
// dashboard is only as honest as the numbers behind it — an operator who negotiated different
// rates, or who runs a local model for free, edits application.yml instead of the code.
//
// A model with no entry is priced at zero and still recorded: the call count stays true even
// when the money doesn't. That's the right failure mode for a dashboard — a missing price
// understates spend visibly (a column of $0.00 against a real call count) rather than breaking
// the request that made the call.
@ConfigurationProperties(prefix = "studyloop.pricing")
public record PricingProperties(Map<String, ModelPrice> models) {

    private static final ModelPrice FREE = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO);

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

    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        public ModelPrice {
            inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
        }

        // Cost of one call. Prices are per million tokens, so the division happens once at the
        // end — six decimal places, matching ai_usage_events.cost_usd.
        public BigDecimal cost(int inputTokens, int outputTokens) {
            BigDecimal in = inputPerMillion.multiply(BigDecimal.valueOf(Math.max(inputTokens, 0)));
            BigDecimal out = outputPerMillion.multiply(BigDecimal.valueOf(Math.max(outputTokens, 0)));
            return in.add(out).divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        }
    }
}
