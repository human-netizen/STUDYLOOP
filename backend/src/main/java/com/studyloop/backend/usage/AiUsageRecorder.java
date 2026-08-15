package com.studyloop.backend.usage;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// What the provider clients call after a billable request, with the token counts the provider
// reported back. The operation is taken from AiUsageContext when a caller set one, and otherwise
// from the fallback the client passes — enough for methods that already say what they are
// (embed vs embedQuery, complete vs streamComplete).
//
// Bookkeeping never breaks the thing it is keeping books on: any failure here is logged and
// swallowed. A student mid-question does not care that the ledger is having a bad day, and the
// dashboard undercounting is a far smaller problem than a 500 on a working answer.
@Component
@RequiredArgsConstructor
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private final AiUsageLedger ledger;

    public void record(String provider, String model, AiOperation fallbackOperation,
                       int inputTokens, int outputTokens) {
        try {
            ledger.write(provider, model, AiUsageContext.current(fallbackOperation),
                    inputTokens, outputTokens);
        } catch (RuntimeException e) {
            log.warn("Could not record {} usage for model {}: {}", provider, model, e.getMessage());
        }
    }
}
