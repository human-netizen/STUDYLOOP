package com.studyloop.backend.document;

import com.studyloop.backend.config.DuplicateProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Phase 29.2 — "you already have this lecture", reported and never enforced.
//
// **Deduplication before this was exact.** `unique (course_space_id, sha256)` catches the same
// bytes twice and nothing else, and the cases that actually happen are not the same bytes: a
// `.pptx` and the PDF somebody exported from it, last year's slides re-uploaded with a new date on
// the title page, a course with two instructors who both post the reading. Each of those doubles
// a stretch of the corpus, splits a question's candidates across two copies so neither ranks as
// well as the single copy would have, and shows the reader two citations to one sentence.
//
// **It never refuses.** A course's corpus is curated by a person and two similar lectures are a
// legitimate thing to have — the same document at two page ranges, a chapter and the summary of
// it. Refusing on a similarity score would mean being wrong in the direction that loses somebody's
// upload, so the result is a sentence on the row and nothing else reads it.
@Service
@RequiredArgsConstructor
public class NearDuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(NearDuplicateDetector.class);

    private final JdbcTemplate jdbc;
    private final DocumentRepository documentRepository;
    private final DuplicateProperties properties;

    // What share of one document is already somewhere else in the course, and where.
    public record Match(UUID documentId, String filename, double coverage) { }

    // Runs after READY, and swallows its own failure for the reason the summary does: this is a
    // remark about a document that is already fully usable, and a provider or query fault must not
    // retroactively mark it FAILED.
    @Transactional
    public void report(UUID courseId, UUID documentId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null) {
                return;
            }
            Match match = closestMatch(courseId, documentId);
            boolean report = match != null && match.coverage() >= properties.reportThreshold();
            // Written either way, because a re-ingest can move a document out of this as easily as
            // into it — a narrower page range (25.1), or the document it resembled being deleted.
            // Only setting the columns would leave the earlier verdict on the row forever, which is
            // the failure mode of every cache that is written on success and never on absence.
            //
            // **Through the repository, not the JdbcTemplate that ran the query above.** The
            // ingestion pipeline holds this same row as a managed entity for the length of the
            // ingest, so a native `update documents set …` here is undone by Hibernate's next
            // flush: the entity is dirty from the status writes, and Hibernate's update statement
            // names every column, including the two this just set from a snapshot taken before it
            // did. That is DocumentStatusService's warning read backwards, and it cost an
            // afternoon in both directions.
            document.setNearDuplicateOf(report ? match.documentId() : null);
            document.setNearDuplicateScore(report ? match.coverage() : null);
            documentRepository.saveAndFlush(document);
            if (report) {
                log.info("Document {} looks like {} ({}): {}% of its sampled passages are already there",
                        documentId, match.documentId(), match.filename(),
                        Math.round(match.coverage() * 100));
            }
        } catch (Exception e) {
            log.warn("Near-duplicate check failed for document {}", documentId, e);
        }
    }

    // The course document that most of this one is already in, or null.
    //
    // **The metric is coverage, not average similarity.** Averaging every pair would let a long
    // document with one identical section look no more duplicated than one that shares a heading,
    // because the rest of the document drags the mean down either way. What the question actually
    // is — "how much of this do I already have" — is a count: for each sampled passage, is there
    // anything anywhere else in the course that is essentially the same passage?
    //
    // One nearest-neighbour lookup per sampled chunk, each of which walks the HNSW index the
    // retriever already maintains. That is the whole cost, and `sampleChunks` caps it: a 300-page
    // book has thousands of chunks and the answer does not get truer after the first hundred.
    public Match closestMatch(UUID courseId, UUID documentId) {
        List<Match> matches = jdbc.query("""
                with numbered as (
                    select c.id, c.embedding,
                           row_number() over (order by c.chunk_index) as rn,
                           count(*) over () as total
                    from document_chunks c
                    where c.document_id = ?
                      and c.modality = 'TEXT'
                      and c.embedding is not null
                ),
                -- Every nth chunk rather than the first n. The front of a scanned book is its
                -- title page, its table of contents and its copyright notice, and those match the
                -- front of every other scanned book.
                mine as (
                    select id, embedding
                    from numbered
                    where (rn - 1) % greatest(1, ceil(total::numeric / ?)::int) = 0
                ),
                nearest as (
                    select other.document_id, other.similarity
                    from mine m
                    cross join lateral (
                        select c.document_id,
                               1 - (c.embedding <=> m.embedding) as similarity
                        from document_chunks c
                        join documents d on d.id = c.document_id
                        where d.course_space_id = ?
                          and c.document_id <> ?
                          and d.status in ('READY', 'RETIRED')
                          -- The same visibility rule retrieval runs on, for the same reason and
                          -- one more: the report names a filename, so comparing against a
                          -- classmate's private note would tell the uploader that note exists.
                          and (d.visibility = 'COURSE'
                               or d.uploaded_by = (select uploaded_by from documents where id = ?))
                          and c.modality = 'TEXT'
                          and c.embedding is not null
                        order by c.embedding <=> m.embedding
                        limit 1
                    ) other
                )
                select n.document_id,
                       d.filename,
                       count(*) filter (where n.similarity >= ?)::numeric
                           / (select greatest(count(*), 1) from mine) as coverage
                from nearest n
                join documents d on d.id = n.document_id
                group by n.document_id, d.filename
                order by coverage desc
                limit 1
                """,
                (rs, row) -> new Match(
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getDouble("coverage")),
                documentId, properties.sampleChunks(), courseId, documentId, documentId,
                properties.chunkFloor());
        return matches.isEmpty() ? null : matches.get(0);
    }
}
