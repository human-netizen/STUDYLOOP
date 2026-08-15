package com.studyloop.backend.usage.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// What /admin/costs answers with: what has been spent, on which feature, on which day, and how
// much of the bill the semantic cache is keeping off the invoice.
public record CostSummaryResponse(
        // How far back the spend figures reach. The cache block ignores it — see CacheStats.
        int windowDays,
        Totals totals,
        // Most expensive feature first, so the line worth attacking is the top one.
        List<OperationCost> byOperation,
        // Ascending by date, with days of no activity simply absent.
        List<DailyCost> daily,
        CacheStats cache
) {

    public record Totals(long calls, long inputTokens, long outputTokens, BigDecimal costUsd) { }

    public record OperationCost(String operation, long calls, long inputTokens, long outputTokens,
                                BigDecimal costUsd) { }

    public record DailyCost(LocalDate day, long calls, BigDecimal costUsd) { }

    // The cache's side of the ledger. Cumulative rather than windowed: an entry records how often
    // it has ever been reused, with no history of when, so pairing those hits with a 30-day call
    // count would produce a hit rate that means nothing.
    //
    // `estimatedSavedUsd` is exactly what its name says — hits × the average price of a grounded
    // answer. The real avoided cost is unknowable (the call was never made), and this is the
    // honest approximation of it.
    public record CacheStats(long entries, long hits, long answersFromModel, double hitRate,
                             BigDecimal estimatedSavedUsd) { }
}
