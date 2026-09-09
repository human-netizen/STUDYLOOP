package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// Phase 28.4 — three reads over data that already exists. No provider call, no new column, no
// migration: this whole sub-phase is a query against `document_chunks.section_path`,
// `document_terms` and `question_event_documents`.
//
// Native SQL rather than JPA for the reason the rest of this project uses it: these are grouped
// aggregates with `filter` clauses and a containment join, which is what the database is for, and
// none of the three shapes is an entity anybody wants managed.
//
// Every query carries the same two scope clauses the retrieval branches do — the course, and
// 16.3's `visibility = 'COURSE' or uploaded_by = ?`. A course outline that listed somebody's
// photographed notebook would be the same leak by a quieter route.
@Repository
@RequiredArgsConstructor
class CourseOutlineRepository {

    private final JdbcTemplate jdbc;

    // The documents themselves, with their page and chunk counts and their all-time question
    // count. One statement: the question count is a scalar subquery rather than a join so a
    // document with no questions stays in the result with a zero — which is the row this page
    // exists to show.
    List<DocumentRow> documents(UUID courseId, UUID actorId) {
        return jdbc.query("""
                select d.id, d.filename, coalesce(d.page_count, 0) as page_count,
                       (select count(*) from document_chunks c where c.document_id = d.id)
                           as chunk_count,
                       (select count(*) from question_event_documents qed
                         where qed.document_id = d.id) as question_count
                from documents d
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                order by d.filename
                """, DOCUMENT_MAPPER, courseId, actorId);
    }

    // Every section of every document in the course, in document order.
    //
    // `min(page_number)` and `max(coalesce(page_end, page_number))` are the span: a chunk may cover
    // a page range (26.x), so the end of a section is the furthest page any of its chunks reaches
    // rather than the first page of its last chunk.
    //
    // Ordered by the smallest `chunk_index` in the section, which is what puts sections in the
    // order the document has them rather than in alphabetical order of their headings — "1.
    // Introduction" and "10. Treaps" sort the wrong way round as text.
    List<SectionRow> sections(UUID courseId, UUID actorId) {
        return jdbc.query("""
                select c.document_id, c.section_path,
                       min(c.page_number) as first_page,
                       max(coalesce(c.page_end, c.page_number)) as last_page,
                       count(*) as chunk_count,
                       min(c.chunk_index) as position
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.section_path is not null
                  and c.modality = 'TEXT'
                group by c.document_id, c.section_path
                order by c.document_id, position
                """, SECTION_MAPPER, courseId, actorId);
    }

    // Which glossary terms appear in which section.
    //
    // **The generator never recorded this, so it is worked out by looking.** 8.2 extracts a
    // document's terms from the document; nothing ties a term to a section, and adding a column
    // would mean re-running extraction over every document already ingested. A containment match
    // against the section's own chunk text answers the same question from what is already stored.
    //
    // The cost is a nested loop of terms against chunks *within one course* — a dozen terms per
    // document against a few hundred chunks — evaluated on a page somebody opens, not on a question
    // somebody asks. `distinct` because a term appearing in four chunks of a section is one term.
    List<TermRow> sectionTerms(UUID courseId, UUID actorId) {
        return jdbc.query("""
                select distinct c.document_id, c.section_path, t.term, t.term_index
                from document_terms t
                join document_chunks c on c.document_id = t.document_id
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.section_path is not null
                  and c.modality = 'TEXT'
                  and position(lower(t.term) in lower(c.content)) > 0
                order by c.document_id, c.section_path, t.term_index
                """, TERM_MAPPER, courseId, actorId);
    }

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, row) -> new DocumentRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("filename"),
            rs.getInt("page_count"),
            rs.getInt("chunk_count"),
            rs.getInt("question_count"));

    private static final RowMapper<SectionRow> SECTION_MAPPER = (rs, row) -> new SectionRow(
            UUID.fromString(rs.getString("document_id")),
            rs.getString("section_path"),
            (Integer) rs.getObject("first_page"),
            (Integer) rs.getObject("last_page"),
            rs.getInt("chunk_count"));

    private static final RowMapper<TermRow> TERM_MAPPER = (rs, row) -> new TermRow(
            UUID.fromString(rs.getString("document_id")),
            rs.getString("section_path"),
            rs.getString("term"));

    record DocumentRow(UUID documentId, String filename, int pageCount, int chunkCount,
                       int questionCount) { }

    record SectionRow(UUID documentId, String sectionPath, Integer firstPage, Integer lastPage,
                      int chunkCount) { }

    record TermRow(UUID documentId, String sectionPath, String term) { }
}
