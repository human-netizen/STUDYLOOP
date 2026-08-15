package com.studyloop.backend.usage;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.usage.dto.CostSummaryResponse;
import com.studyloop.backend.usage.dto.CostSummaryResponse.DailyCost;
import com.studyloop.backend.usage.dto.CostSummaryResponse.OperationCost;
import com.studyloop.backend.usage.dto.CostSummaryResponse.Totals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Assembles /admin/costs. Read-only throughout: it reports what already happened and changes
// nothing, which is the property that makes it safe to leave open on a screen during a demo.
@Service
@RequiredArgsConstructor
public class CostService {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    // A year of daily rows is still a small response, and nothing here is worth paging.
    private static final int MAX_WINDOW_DAYS = 365;

    private final AiUsageStatsRepository statsRepository;
    private final SemanticCacheService semanticCache;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CostSummaryResponse summary(int windowDays) {
        int days = clampWindow(windowDays);
        Instant since = clock.instant().minus(Duration.ofDays(days));

        List<OperationCost> byOperation = statsRepository.costByOperation(since);
        List<DailyCost> daily = statsRepository.costByDay(since);

        return new CostSummaryResponse(days, totalOf(byOperation), byOperation, daily, cacheStats());
    }

    // Summed from the per-operation rows rather than re-queried: one pass over the same numbers
    // the table shows, so the total can never disagree with the rows above it.
    private static Totals totalOf(List<OperationCost> byOperation) {
        long calls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (OperationCost row : byOperation) {
            calls += row.calls();
            inputTokens += row.inputTokens();
            outputTokens += row.outputTokens();
            cost = cost.add(row.costUsd());
        }
        return new Totals(calls, inputTokens, outputTokens, cost);
    }

    // The cache's own scoreboard: how many questions it answered, against how many the model had
    // to. Both sides are all-time — see CacheStats for why mixing a window in here would lie.
    private CostSummaryResponse.CacheStats cacheStats() {
        SemanticCacheService.CacheStats cache = semanticCache.stats();
        long hits = cache.hits();
        long fromModel = statsRepository.answersFromModel();
        long lookups = hits + fromModel;

        double hitRate = lookups == 0 ? 0.0 : (double) hits / lookups;
        BigDecimal saved = statsRepository.averageAnswerCost()
                .multiply(BigDecimal.valueOf(hits))
                .setScale(6, RoundingMode.HALF_UP);

        return new CostSummaryResponse.CacheStats(cache.entries(), hits, fromModel, hitRate, saved);
    }

    private static int clampWindow(int windowDays) {
        if (windowDays <= 0) {
            return DEFAULT_WINDOW_DAYS;
        }
        return Math.min(windowDays, MAX_WINDOW_DAYS);
    }
}
