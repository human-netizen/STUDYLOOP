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
        write(provider, model, fallbackOperation, inputTokens, outputTokens, 0);
    }

    // For a call the provider bills per search rather than per token — reranking, as of Phase 12.3.
    // The token counts are zero because there are none, not because they went unread: a rerank call
    // has no generated output and is not invoiced on its input length. Writing the batch size in
    // there to make the row look populated would inflate the per-user token budget, which sums that
    // column, and charge a student for a stage they did not choose to run.
    public void recordSearch(String provider, String model, AiOperation fallbackOperation,
                             int searchUnits) {
        write(provider, model, fallbackOperation, 0, 0, searchUnits);
    }

    private void write(String provider, String model, AiOperation fallbackOperation,
                       int inputTokens, int outputTokens, int searchUnits) {
        try {
            ledger.write(provider, model, AiUsageContext.current(fallbackOperation),
                    AiUsageContext.currentActor(), inputTokens, outputTokens, searchUnits);
        } catch (RuntimeException e) {
            log.warn("Could not record {} usage for model {}: {}", provider, model, e.getMessage());
        }
    }
}
