package com.studyloop.backend.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Native SQL for the same reason ChunkSearchRepository and SemanticCacheRepository are:
// question_embedding is a pgvector column Hibernate cannot map. The aggregate queries would map
// fine, but splitting one table across two access styles buys nothing except a second place to
// look when a column changes.
@Repository
@RequiredArgsConstructor
class QuestionEventRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<LectureHeatRow> HEAT_MAPPER = (rs, row) -> new LectureHeatRow(
            UUID.fromString(rs.getString("document_id")),
            rs.getString("filename"),
            rs.getInt("question_count"),
            rs.getInt("distinct_askers"),
            rs.getTimestamp("last_asked_at").toInstant());

    // ── writes (chat request path) ──────────────────────────────────────────────────────────

    void insertEvent(UUID id, UUID courseId, UUID askedBy, String question,
                     String questionVectorLiteral, boolean grounded, Double topSimilarity) {
        jdbc.update("""
                insert into question_events
                    (id, course_space_id, asked_by, question, question_embedding, grounded, top_similarity)
                values (?, ?, ?, ?, cast(? as vector), ?, ?)
                """,
                id, courseId, askedBy, question, questionVectorLiteral, grounded, topSimilarity);
    }

    // One statement for the whole citation set; a question grounded on six chunks usually spans
    // two or three documents, so this is a handful of rows at most.
    void insertEventDocuments(UUID eventId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<Object[]> batch = documentIds.stream()
                .map(documentId -> new Object[] { eventId, documentId })
                .toList();
        jdbc.batchUpdate(
                "insert into question_event_documents (question_event_id, document_id) values (?, ?)",
                batch);
    }

    // ── reads (instructor page) ─────────────────────────────────────────────────────────────

    // Per-lecture totals: how many questions landed on each document and how many separate
    // students asked them. Left join from documents so a lecture nobody has asked about still
    // appears — a zero row is a real finding (either untouched material or a document that
    // retrieval never surfaces), and dropping it would hide both.
    //
    // Uploaded documents only. The corpus also holds text written back from accepted forum
    // answers, and while those do absorb questions, this page asks "which of the lectures you
    // taught is the class stuck on" — a row an instructor cannot revise is not an answer to it.
    List<LectureHeatRow> lectureHeat(UUID courseId, Instant since) {
        return jdbc.query("""
                select d.id                                   as document_id,
                       d.filename                             as filename,
                       count(distinct e.id)                   as question_count,
                       count(distinct e.asked_by)             as distinct_askers,
                       coalesce(max(e.created_at), d.created_at) as last_asked_at
                from documents d
                left join question_event_documents ed on ed.document_id = d.id
                left join question_events e
                       on e.id = ed.question_event_id
                      and e.created_at >= ?
                where d.course_space_id = ?
                  and d.source = 'UPLOAD'
                group by d.id, d.filename, d.created_at
                order by question_count desc, d.filename
                """, HEAT_MAPPER, Timestamp.from(since), courseId);
    }

    // Phase 20.3 — how many times *this* student has already asked *this* course something that
    // means the same thing, and what they typed the last time. Runs on the chat request path, on
    // the vector the cache probe already paid for.
    //
    // It is a filtered scan and not a nearest-neighbour search, which is what keeps V15's decision
    // to build no HNSW index on this column correct rather than merely unrevisited. The rows
    // scanned are one student's questions in one course — tens, not the table — and an ANN index
    // could not answer this anyway: the question is "all of them above 0.82", not "the closest
    // five", and the index would be rebuilt on every insert on the same request path.
    //
    // No time floor, for the same reason clustering has none: a student who asked this in week two
    // and is asking again in week nine is the clearest case the feature exists for, and a 30-day
    // window would hide exactly that one.
    Optional<RecurrenceRow> recurrence(UUID courseId, UUID askerId, String vectorLiteral,
                                       double threshold) {
        List<RecurrenceRow> rows = jdbc.query("""
                select count(*)                                        as times,
                       max(created_at)                                 as last_asked_at,
                       (array_agg(question order by created_at desc))[1] as last_question
                from question_events
                where course_space_id = ?
                  and asked_by = ?
                  and question_embedding is not null
                  and 1 - (question_embedding <=> cast(? as vector)) >= ?
                """,
                (rs, row) -> new RecurrenceRow(
                        rs.getInt("times"),
                        rs.getTimestamp("last_asked_at") == null
                                ? null : rs.getTimestamp("last_asked_at").toInstant(),
                        rs.getString("last_question")),
                courseId, askerId, vectorLiteral, threshold);
        // An aggregate always returns one row, and it reads as zero matches when there were none.
        return rows.stream().filter(row -> row.times() > 0).findFirst();
    }

    // Phase 20.2 — stamp the refusal the student chose to escalate. Scoped to the course as well
    // as the id, because the id came from a client: an escalation must not be able to mark a row
    // in a course the caller is not in.
    void markEscalated(UUID courseId, UUID eventId, Instant at) {
        jdbc.update("""
                update question_events
                set escalated_at = ?
                where id = ? and course_space_id = ? and escalated_at is null
                """, Timestamp.from(at), eventId, courseId);
    }

    boolean existsInCourse(UUID courseId, UUID eventId) {
        Integer found = jdbc.queryForObject(
                "select count(*) from question_events where id = ? and course_space_id = ?",
                Integer.class, eventId, courseId);
        return found != null && found > 0;
    }

    Totals totals(UUID courseId, Instant since) {
        return jdbc.queryForObject("""
                select count(*)                                              as asked,
                       count(*) filter (where not grounded)                  as ungrounded,
                       count(*) filter (where escalated_at is not null)      as escalated,
                       count(distinct asked_by)                              as askers
                from question_events
                where course_space_id = ? and created_at >= ?
                """,
                (rs, row) -> new Totals(rs.getInt("asked"), rs.getInt("ungrounded"),
                        rs.getInt("escalated"), rs.getInt("askers")),
                courseId, Timestamp.from(since));
    }

    // The questions the corpus could not answer, newest first, each carrying the forum thread it
    // was escalated into if it was one — which is what turns this list from a complaint into a
    // queue. The join is scoped to the same course as well as the same event: the id comes from a
    // client, and a thread from another course must never surface on this page.
    List<UngroundedRow> ungrounded(UUID courseId, Instant since, int limit) {
        return jdbc.query("""
                select e.id, e.question, e.top_similarity, e.created_at, e.escalated_at,
                       t.id as thread_id
                from question_events e
                left join forum_threads t
                       on t.question_event_id = e.id
                      and t.course_space_id = e.course_space_id
                where e.course_space_id = ? and e.created_at >= ? and not e.grounded
                order by e.created_at desc
                limit ?
                """,
                (rs, row) -> new UngroundedRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("question"),
                        (Double) rs.getObject("top_similarity"),
                        rs.getTimestamp("created_at").toInstant(),
                        uuidOrNull(rs.getString("thread_id")),
                        rs.getTimestamp("escalated_at") != null),
                courseId, Timestamp.from(since), limit);
    }

    // ── clustering input/output ─────────────────────────────────────────────────────────────

    // The newest `limit` questions that actually carry a vector, returned oldest-first so the
    // clusterer meets each topic in the order the class first raised it.
    List<QuestionVector> recentVectors(UUID courseId, Instant since, int limit) {
        return jdbc.query("""
                select id, question, asked_by, grounded, created_at, question_embedding::text as vec
                from (
                    select id, question, asked_by, grounded, created_at, question_embedding
                    from question_events
                    where course_space_id = ? and created_at >= ? and question_embedding is not null
                    order by created_at desc
                    limit ?
                ) recent
                order by created_at
                """,
                (rs, row) -> new QuestionVector(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("question"),
                        UUID.fromString(rs.getString("asked_by")),
                        rs.getBoolean("grounded"),
                        rs.getTimestamp("created_at").toInstant(),
                        parseVector(rs.getString("vec"))),
                courseId, Timestamp.from(since), limit);
    }

    // Which documents each of those questions was grounded on, so a cluster can report the
    // lectures behind it without a second round trip per member.
    List<EventDocument> documentsFor(UUID courseId, Instant since) {
        return jdbc.query("""
                select ed.question_event_id, ed.document_id
                from question_event_documents ed
                join question_events e on e.id = ed.question_event_id
                where e.course_space_id = ? and e.created_at >= ?
                """,
                (rs, row) -> new EventDocument(
                        UUID.fromString(rs.getString("question_event_id")),
                        UUID.fromString(rs.getString("document_id"))),
                courseId, Timestamp.from(since));
    }

    // Replace-in-place: clustering is not incremental, so yesterday's grouping is not a partial
    // answer to be merged, it is a stale one to be discarded. Runs inside the recompute's
    // transaction, so a reader never sees the gap between the delete and the inserts.
    void replaceClusters(UUID courseId, List<ClusterRow> clusters) {
        jdbc.update("delete from question_clusters where course_space_id = ?", courseId);
        if (clusters.isEmpty()) {
            return;
        }
        List<Object[]> batch = clusters.stream()
                .map(cluster -> new Object[] {
                        UUID.randomUUID(), courseId, cluster.label(), cluster.questionCount(),
                        cluster.ungroundedCount(), cluster.distinctAskers(), cluster.documentCountsJson(),
                        Timestamp.from(cluster.lastAskedAt()) })
                .toList();
        jdbc.batchUpdate("""
                insert into question_clusters
                    (id, course_space_id, label, question_count, ungrounded_count,
                     distinct_askers, document_counts, last_asked_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """, batch);
    }

    List<StoredCluster> clusters(UUID courseId, int limit) {
        return jdbc.query("""
                select label, question_count, ungrounded_count, distinct_askers,
                       document_counts::text as document_counts, last_asked_at
                from question_clusters
                where course_space_id = ?
                order by question_count desc, last_asked_at desc
                limit ?
                """,
                (rs, row) -> new StoredCluster(
                        rs.getString("label"),
                        rs.getInt("question_count"),
                        rs.getInt("ungrounded_count"),
                        rs.getInt("distinct_askers"),
                        rs.getString("document_counts"),
                        rs.getTimestamp("last_asked_at").toInstant()),
                courseId, limit);
    }

    // Staleness, answered in one round trip: the newest question against the newest cluster.
    // Both null-safe, because "no questions yet" and "never clustered" are both normal.
    Optional<Instant> newestQuestionAt(UUID courseId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "select max(created_at) from question_events where course_space_id = ?",
                Timestamp.class, courseId)).map(Timestamp::toInstant);
    }

    Optional<Instant> clustersComputedAt(UUID courseId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "select max(computed_at) from question_clusters where course_space_id = ?",
                Timestamp.class, courseId)).map(Timestamp::toInstant);
    }

    // Courses the scheduler should look at: any that have been asked something at all. Cheap
    // enough to over-collect here — recompute() itself re-checks staleness before doing work.
    List<UUID> coursesWithQuestions(Instant since) {
        return jdbc.query("""
                select distinct course_space_id
                from question_events
                where created_at >= ?
                """,
                (rs, row) -> UUID.fromString(rs.getString("course_space_id")),
                Timestamp.from(since));
    }

    // Outer-join columns come back null for a question nobody escalated, which is the common case.
    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    // pgvector renders a vector as "[0.1,0.2,...]" when cast to text — the same literal form
    // VectorSupport writes. Parsing it back beats adding a pgvector JDBC type mapping for the
    // one query that needs the raw floats.
    private static float[] parseVector(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    record LectureHeatRow(UUID documentId, String filename, int questionCount, int distinctAskers,
                          Instant lastAskedAt) { }

    // `escalated` counts refusals a student then asked from general knowledge (20.2). It is a
    // subset of `ungrounded` and never of `asked` alone, because only a refusal offers the
    // button — the ratio between the two is the measurement: how often a gap in the materials
    // was worth a second click.
    record Totals(int asked, int ungrounded, int escalated, int askers) { }

    record UngroundedRow(UUID eventId, String question, Double topSimilarity, Instant askedAt,
                         UUID threadId, boolean escalated) { }

    // `times` counts prior questions only — the current one has not been logged yet when this
    // runs, which is what makes "you have asked this twice before" literally true.
    record RecurrenceRow(int times, Instant lastAskedAt, String lastQuestion) { }

    record QuestionVector(UUID id, String question, UUID askedBy, boolean grounded, Instant askedAt,
                          float[] vector) { }

    record EventDocument(UUID eventId, UUID documentId) { }

    record ClusterRow(String label, int questionCount, int ungroundedCount, int distinctAskers,
                      String documentCountsJson, Instant lastAskedAt) { }

    record StoredCluster(String label, int questionCount, int ungroundedCount, int distinctAskers,
                         String documentCountsJson, Instant lastAskedAt) { }
}
