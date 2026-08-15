package com.studyloop.backend.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Native SQL for the same reason ChunkSearchRepository is: question_embedding is a pgvector
// column Hibernate cannot map, and the whole point of the table is ordering by distance on it.
@Repository
@RequiredArgsConstructor
class SemanticCacheRepository {

    private final JdbcTemplate jdbc;

    // The single closest previous question in this course, whatever its distance — the service
    // decides whether it is close enough. Asking for one row and judging it afterwards is what
    // lets the HNSW index answer the query; a `where similarity >= 0.97` predicate would have to
    // be evaluated per row and would push Postgres toward a sequential scan instead.
    Optional<CacheRow> findNearest(UUID courseId, String queryVectorLiteral, Instant notOlderThan) {
        List<CacheRow> rows = jdbc.query("""
                select id, answer, citations,
                       1 - (question_embedding <=> cast(? as vector)) as similarity
                from chat_cache_entries
                where course_space_id = ?
                  and created_at >= ?
                order by question_embedding <=> cast(? as vector)
                limit 1
                """,
                (rs, row) -> new CacheRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("answer"),
                        rs.getString("citations"),
                        rs.getDouble("similarity")),
                queryVectorLiteral, courseId, Timestamp.from(notOlderThan), queryVectorLiteral);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    void insert(UUID courseId, String question, String questionVectorLiteral, String answer,
                String citationsJson) {
        jdbc.update("""
                insert into chat_cache_entries
                    (id, course_space_id, question, question_embedding, answer, citations)
                values (?, ?, ?, cast(? as vector), ?, cast(? as jsonb))
                """,
                UUID.randomUUID(), courseId, question, questionVectorLiteral, answer, citationsJson);
    }

    // hit_count is what the dashboard's savings figure is built on, so a served answer has to
    // leave a mark even though nothing else about the row changes.
    void markHit(UUID entryId) {
        jdbc.update(
                "update chat_cache_entries set hit_count = hit_count + 1, last_hit_at = now() where id = ?",
                entryId);
    }

    // Called when a course's materials change. Wholesale, not selective: a new document can turn
    // any previously unanswerable question answerable, so there is no subset that is safely stale.
    int deleteByCourse(UUID courseId) {
        return jdbc.update("delete from chat_cache_entries where course_space_id = ?", courseId);
    }

    long countEntries() {
        Long count = jdbc.queryForObject("select count(*) from chat_cache_entries", Long.class);
        return count == null ? 0L : count;
    }

    long totalHits() {
        Long hits = jdbc.queryForObject(
                "select coalesce(sum(hit_count), 0) from chat_cache_entries", Long.class);
        return hits == null ? 0L : hits;
    }

    // A row as stored: citations still raw JSON, similarity relative to the probe that found it.
    record CacheRow(UUID id, String answer, String citationsJson, double similarity) { }
}
