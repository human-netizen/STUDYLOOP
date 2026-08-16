package com.studyloop.backend.usage;

import com.studyloop.backend.usage.dto.CostSummaryResponse.DailyCost;
import com.studyloop.backend.usage.dto.CostSummaryResponse.OperationCost;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Everything that reads the ledger in aggregate: the dashboard's numbers, and the one query the
// per-user token budget asks before an expensive request. Native SQL, because grouping by day
// needs date_trunc and summing numeric into BigDecimal without a round trip through double needs
// the driver's own mapping — neither is expressible in JPQL, so this follows
// ChunkSearchRepository's precedent and drops to JdbcTemplate. Everything here is read-only;
// AiUsageEventRepository does the writing.
@Repository
@RequiredArgsConstructor
class AiUsageStatsRepository {

    // The two operations that answer a student's question. Both are "the model was asked
    // something a cache hit could have answered instead", which is what the savings figure
    // compares against.
    private static final String ANSWER_OPERATIONS = "('CHAT', 'CHAT_STREAM')";

    private final JdbcTemplate jdbc;

    List<OperationCost> costByOperation(Instant since) {
        return jdbc.query("""
                select operation,
                       count(*)                        as calls,
                       coalesce(sum(input_tokens), 0)  as input_tokens,
                       coalesce(sum(output_tokens), 0) as output_tokens,
                       coalesce(sum(cost_usd), 0)      as cost_usd
                from ai_usage_events
                where occurred_at >= ?
                group by operation
                order by cost_usd desc, calls desc
                """,
                (rs, row) -> new OperationCost(
                        rs.getString("operation"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getBigDecimal("cost_usd")),
                Timestamp.from(since));
    }

    // One row per day that actually had activity — the client draws the gaps, rather than the
    // query inventing zero rows for days nobody used the app.
    List<DailyCost> costByDay(Instant since) {
        return jdbc.query("""
                select cast(date_trunc('day', occurred_at) as date) as day,
                       count(*)                   as calls,
                       coalesce(sum(cost_usd), 0) as cost_usd
                from ai_usage_events
                where occurred_at >= ?
                group by 1
                order by 1
                """,
                (rs, row) -> new DailyCost(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("calls"),
                        rs.getBigDecimal("cost_usd")),
                Timestamp.from(since));
    }

    // How many questions were answered by actually paying the model. Deliberately not windowed:
    // it is compared against a cache hit counter that has no history either, and a hit rate that
    // mixed a 30-day numerator with an all-time denominator would be nonsense.
    long answersFromModel() {
        Long count = jdbc.queryForObject(
                "select count(*) from ai_usage_events where operation in " + ANSWER_OPERATIONS,
                Long.class);
        return count == null ? 0L : count;
    }

    // What one user has spent inside the budget window, and when the oldest call still counted
    // was made. Both in one statement because the second is only meaningful alongside the first:
    // the window rolls, so the moment the user gets headroom back is when that oldest row ages
    // out of it. Deliberately one row and one index range scan — this runs before every gated
    // request, so it must cost about as much as the authorization check next to it.
    UserSpend spendSince(UUID userId, Instant since) {
        UserSpend spend = jdbc.queryForObject("""
                select coalesce(sum(input_tokens + output_tokens), 0) as tokens,
                       min(occurred_at)                               as oldest
                from ai_usage_events
                where user_id = ? and occurred_at >= ?
                """,
                (rs, row) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    return new UserSpend(rs.getLong("tokens"),
                            oldest == null ? null : oldest.toInstant());
                },
                userId, Timestamp.from(since));
        return spend == null ? new UserSpend(0L, null) : spend;
    }

    // oldest is null exactly when tokens is 0 — the user has made no billable call in the window.
    record UserSpend(long tokens, Instant oldest) { }

    // The average price of one grounded answer, which is what a cache hit avoids paying.
    BigDecimal averageAnswerCost() {
        BigDecimal average = jdbc.queryForObject(
                "select coalesce(avg(cost_usd), 0) from ai_usage_events where operation in "
                        + ANSWER_OPERATIONS,
                BigDecimal.class);
        return average == null ? BigDecimal.ZERO : average;
    }
}
