package com.studyloop.backend.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phase 28.3 — reads and writes for `answer_feedback`.
//
// JdbcTemplate rather than JPA, for the reason every other jsonb column in this project is written
// that way (V14's cache, V15's cluster counts): the citation list is stored verbatim as jsonb, and
// `cast(? as jsonb)` in one statement is simpler than a type mapping that exists to serve a single
// column nothing queries into.
@Repository
@RequiredArgsConstructor
class AnswerFeedbackRepository {

    private final JdbcTemplate jdbc;

    // One verdict per member per answer: clicking again changes your mind rather than stuffing the
    // ballot. `on conflict` needs the index it targets to exist, and the partial index in V30 is a
    // valid arbiter here because the same predicate appears in the `where` clause below.
    //
    // The rows this cannot deduplicate are the ones with a null event id — question logging off —
    // and that is correct: with no event to identify, two verdicts from the same member are two
    // different answers, and the alternative would silently overwrite the first.
    void upsert(UUID id, UUID courseId, UUID questionEventId, UUID submittedBy, boolean helpful,
                String reason, String question, String citationsJson) {
        jdbc.update("""
                insert into answer_feedback
                    (id, course_space_id, question_event_id, submitted_by, helpful, reason,
                     question, citations)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict (question_event_id, submitted_by) where question_event_id is not null
                do update set helpful    = excluded.helpful,
                              reason     = excluded.reason,
                              citations  = excluded.citations,
                              created_at = now()
                """, id, courseId, questionEventId, submittedBy, helpful, reason, question,
                citationsJson);
    }

    // Reported answers for the instructor's page, newest first.
    //
    // Only the unhelpful ones, and the window is the same one every other block of that report
    // uses. A thumbs-up is stored — it is the denominator, and "3 of 40 answers were reported" is a
    // very different page from "3 answers were reported" — but it is not a row anybody needs to
    // read one at a time.
    List<ComplaintRow> complaints(UUID courseId, Instant since, int limit) {
        return jdbc.query("""
                select question_event_id, question, reason, created_at, citations
                from answer_feedback
                where course_space_id = ?
                  and helpful = false
                  and created_at >= ?
                order by created_at desc
                limit ?
                """, COMPLAINT_MAPPER, courseId, java.sql.Timestamp.from(since), limit);
    }

    // The denominator for the line above the list: how many verdicts of each kind landed in the
    // window. One row, two counts, one scan.
    Tally tally(UUID courseId, Instant since) {
        return jdbc.queryForObject("""
                select count(*) filter (where helpful) as helpful,
                       count(*) filter (where not helpful) as unhelpful
                from answer_feedback
                where course_space_id = ?
                  and created_at >= ?
                """, (rs, row) -> new Tally(rs.getInt("helpful"), rs.getInt("unhelpful")),
                courseId, java.sql.Timestamp.from(since));
    }

    private static final RowMapper<ComplaintRow> COMPLAINT_MAPPER = (rs, row) -> new ComplaintRow(
            rs.getString("question_event_id") == null
                    ? null
                    : UUID.fromString(rs.getString("question_event_id")),
            rs.getString("question"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("citations"));

    // Citations still raw JSON — the service turns them back into records, the way
    // SemanticCacheService does for the same shape.
    record ComplaintRow(UUID questionEventId, String question, String reason, Instant reportedAt,
                        String citationsJson) { }

    record Tally(int helpful, int unhelpful) { }
}
