package com.studyloop.backend.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// The two halves of hybrid search, run as native SQL because both lean on pgvector / Postgres
// full-text features Hibernate doesn't model. Each method returns chunks best-first for a
// single course, already scoped to READY documents; the service fuses the two rankings.
@Repository
@RequiredArgsConstructor
class ChunkSearchRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ChunkHit> ROW_MAPPER = (rs, row) -> new ChunkHit(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("document_id")),
            rs.getString("filename"),
            (Integer) rs.getObject("page_number"),
            rs.getString("content"),
            rs.getInt("token_count"));

    // Approximate nearest neighbours by cosine distance (the HNSW index answers the <=> order).
    // queryVectorLiteral is a pgvector "[...]" text literal cast to vector; chunks without an
    // embedding are skipped so an un-embedded corpus simply yields no vector hits.
    List<ChunkHit> vectorSearch(UUID courseId, String queryVectorLiteral, int limit) {
        return jdbc.query("""
                select c.id, c.document_id, d.filename, c.page_number, c.content, c.token_count
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and c.embedding is not null
                order by c.embedding <=> cast(? as vector)
                limit ?
                """, ROW_MAPPER, courseId, queryVectorLiteral, limit);
    }

    // Lexical matches ranked by ts_rank over the generated content_tsv column (GIN-indexed).
    // plainto_tsquery treats the query as plain words AND-ed together, so only chunks sharing
    // vocabulary with the query come back.
    List<ChunkHit> fullTextSearch(UUID courseId, String query, int limit) {
        return jdbc.query("""
                select c.id, c.document_id, d.filename, c.page_number, c.content, c.token_count
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and c.content_tsv @@ plainto_tsquery('english', ?)
                order by ts_rank(c.content_tsv, plainto_tsquery('english', ?)) desc
                limit ?
                """, ROW_MAPPER, courseId, query, query, limit);
    }
}
