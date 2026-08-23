package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// The ranked lists hybrid search fuses, run as native SQL because they lean on pgvector / Postgres
// full-text features Hibernate doesn't model. Each method returns chunks best-first for a
// single course, already scoped to READY documents; the service fuses the rankings.
//
// Two of them until Phase 17, three now: dense over text, lexical, and dense over page images. The
// two dense queries are the same query with one predicate changed, because a visual chunk differs
// from a text chunk only in what its vector was made from — same column, same index type, same
// page number, same citation. What the modality predicate buys is that the text half stays exactly
// the text half: without it, a course that uploaded a lot of figures would find its twenty dense
// candidates quietly becoming fifteen text ones and five pictures.
//
// Both halves search the whole corpus, forum-derived documents included: an answer the class
// worked out and an instructor accepted is course knowledge, and the point of writing it back
// was for retrieval to find it. `d.source` rides along so a citation can say which it was.
//
// **The one thing not in "the whole corpus" is somebody else's notebook** (Phase 16.3). A
// photographed note starts visible only to the member who uploaded it, so both queries carry
// `visibility = 'COURSE' or uploaded_by = actor`. It is one clause and it is load-bearing: this is
// the only place a private note could reach a stranger, because everything downstream — chat,
// quizzes, flashcards, the search page — is built on these two methods and inherits whatever they
// return. Written into the SQL rather than filtered afterwards, so a caller cannot forget it and
// so the candidate count means what it says.
@Repository
@RequiredArgsConstructor
class ChunkSearchRepository {

    private final JdbcTemplate jdbc;

    // Vector hits carry a cosine similarity; lexical hits don't have one, so it stays null.
    private static final RowMapper<ChunkHit> VECTOR_MAPPER = (rs, row) -> new ChunkHit(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("document_id")),
            rs.getString("filename"),
            DocumentSource.valueOf(rs.getString("source")),
            (Integer) rs.getObject("page_number"),
            (Integer) rs.getObject("page_end"),
            rs.getString("section_path"),
            rs.getString("content"),
            rs.getInt("token_count"),
            ChunkModality.valueOf(rs.getString("modality")),
            (Double) rs.getObject("cosine_similarity"));

    private static final RowMapper<ChunkHit> TEXT_MAPPER = (rs, row) -> new ChunkHit(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("document_id")),
            rs.getString("filename"),
            DocumentSource.valueOf(rs.getString("source")),
            (Integer) rs.getObject("page_number"),
            (Integer) rs.getObject("page_end"),
            rs.getString("section_path"),
            rs.getString("content"),
            rs.getInt("token_count"),
            ChunkModality.valueOf(rs.getString("modality")),
            null);

    // Approximate nearest neighbours by cosine distance (the HNSW index answers the <=> order).
    // queryVectorLiteral is a pgvector "[...]" text literal cast to vector; chunks without an
    // embedding are skipped so an un-embedded corpus simply yields no vector hits. We also select
    // 1 - distance as the cosine similarity so the caller can gate on the top match's strength.
    List<ChunkHit> vectorSearch(UUID courseId, UUID actorId, String queryVectorLiteral, int limit) {
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality,
                       1 - (c.embedding <=> cast(? as vector)) as cosine_similarity
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.modality = 'TEXT'
                  and c.embedding is not null
                order by c.embedding <=> cast(? as vector)
                limit ?
                """, VECTOR_MAPPER, queryVectorLiteral, courseId, actorId, queryVectorLiteral, limit);
    }

    // Every chunk of one section of one document, in document order — the raw material for
    // small-to-big expansion (Phase 13.5). Reads `content`, deliberately: the context header in
    // embed_text is there to be matched, not to be read back to a student six times over.
    List<SectionChunk> sectionChunks(UUID documentId, String sectionPath) {
        return jdbc.query("""
                select id, content
                from document_chunks
                where document_id = ?
                  and section_path = ?
                order by chunk_index
                """, SECTION_MAPPER, documentId, sectionPath);
    }

    private static final RowMapper<SectionChunk> SECTION_MAPPER = (rs, row) ->
            new SectionChunk(UUID.fromString(rs.getString("id")), rs.getString("content"));

    record SectionChunk(UUID id, String content) { }

    // Phase 17.3 — the third ranked list: pages whose *picture* is near the query.
    //
    // The query vector is the same one the dense half searched with, embedded from the same typed
    // question by the same call. That is the cheapest thing in this phase and the least obvious:
    // embed-v4.0 puts text and images in one space, so a question already embedded for text search
    // is, at no extra cost, also a query against every page image in the corpus.
    //
    // Everything else here is a copy of `vectorSearch` with one predicate changed, which is the
    // point rather than duplication to be factored away later: the two lists differ in what their
    // vectors were made from and in nothing else, so they share the column, the index type, the
    // course scope, the READY filter, the visibility clause and the citation fields. A visual chunk
    // is a chunk.
    List<ChunkHit> visualSearch(UUID courseId, UUID actorId, String queryVectorLiteral, int limit) {
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality,
                       1 - (c.embedding <=> cast(? as vector)) as cosine_similarity
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.modality = 'VISUAL'
                  and c.embedding is not null
                order by c.embedding <=> cast(? as vector)
                limit ?
                """, VECTOR_MAPPER, queryVectorLiteral, courseId, actorId, queryVectorLiteral, limit);
    }

    // Lexical matches ranked by ts_rank over the generated content_tsv column (GIN-indexed).
    // plainto_tsquery treats the query as plain words AND-ed together, so only chunks sharing
    // vocabulary with the query come back.
    List<ChunkHit> fullTextSearch(UUID courseId, UUID actorId, String query, int limit) {
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.modality = 'TEXT'
                  and c.content_tsv @@ plainto_tsquery('english', ?)
                order by ts_rank(c.content_tsv, plainto_tsquery('english', ?)) desc
                limit ?
                """, TEXT_MAPPER, courseId, actorId, query, query, limit);
    }
}
