package com.studyloop.backend.usage;

import com.studyloop.backend.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// The write half of usage tracking, split from AiUsageRecorder for the same reason
// DocumentSummaryStore is split from DocumentSummaryService: @Transactional comes from a proxy,
// so a method a bean calls on itself runs with no transaction at all.
//
// REQUIRES_NEW is not decoration here, it is the only propagation that works. Retrieval embeds a
// query inside a @Transactional(readOnly = true) method, and Postgres rejects an INSERT in a
// read-only transaction outright. A suspended, genuinely new transaction also means the ledger
// survives a rollback of whatever the caller was doing — the provider was paid either way, so
// losing the row with the request would understate real spend.
//
// It costs a second pooled connection for the length of one INSERT. That bounds concurrent
// recording at half the pool, which is why nothing here fans out or batches.
@Service
@RequiredArgsConstructor
class AiUsageLedger {

    private final AiUsageEventRepository repository;
    private final PricingProperties pricing;

    // searchUnits is how reranking bills — per call over a batch of passages rather than per token
    // — and is zero for everything else. It is priced but not stored: for every batch size this
    // system sends, one call is one search unit, so a column holding it would be a slower way of
    // writing count(*). The cost it produces is stored, which is what the dashboard sums.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String provider, String model, AiOperation operation, UUID userId,
                      int inputTokens, int outputTokens, int searchUnits) {
        BigDecimal cost = pricing.priceFor(model).cost(inputTokens, outputTokens, searchUnits);

        AiUsageEvent event = new AiUsageEvent();
        event.setProvider(provider);
        event.setModel(model);
        event.setOperation(operation);
        event.setUserId(userId);
        event.setInputTokens(Math.max(inputTokens, 0));
        event.setOutputTokens(Math.max(outputTokens, 0));
        event.setCostUsd(cost);
        repository.save(event);
    }
}
