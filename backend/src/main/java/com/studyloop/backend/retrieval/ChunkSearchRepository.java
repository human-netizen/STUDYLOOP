package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// The ranked lists hybrid search fuses, run as native SQL because they lean on pgvector / Postgres
// full-text features Hibernate doesn't model. Each method returns chunks best-first for a
// single course, already scoped to READY documents; the service fuses the rankings.
//
// Two of them until Phase 17, four now: dense over text, lexical over lexemes, dense over page
// images, and lexical over trigrams. The two dense queries are the same query with one predicate
// changed, because a visual chunk differs from a text chunk only in what its vector was made from —
// same column, same index type, same page number, same citation. What the modality predicate buys
// is that the text half stays exactly the text half: without it, a course that uploaded a lot of
// figures would find its twenty dense candidates quietly becoming fifteen text ones and five
// pictures. The two lexical queries differ in what counts as a match: a shared lexeme, or a shared
// three-letter window, which is the difference between a question spelled right and one spelled
// nearly right.
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
        return vectorSearch(courseId, actorId, queryVectorLiteral, queryVectorLiteral, limit);
    }

    // The same search, ordered by one vector and *scored* by another (Phase 18.2).
    //
    // **This is what makes HyDE safe to gate on, and it is one extra expression rather than a
    // second query.** A hypothetical answer retrieves better than the question that produced it,
    // but a pseudo-document-to-document cosine sits on a visibly higher scale than the
    // question-to-document cosine the confidence gate was calibrated against — so reporting it as
    // the gate's signal would raise every score the gate reads without any of them meaning more,
    // and the refusal rate would drift toward zero with nothing throwing and no test failing.
    //
    // Splitting the two roles costs nothing: the ORDER BY walks the HNSW index with the search
    // vector, and the similarity in the select list is evaluated against the gate vector on the
    // twenty rows that come back. When the caller passes the same vector twice — every call before
    // this phase and every call with the stage off — the value is bit-for-bit what it always was.
    List<ChunkHit> vectorSearch(UUID courseId, UUID actorId, String searchVectorLiteral,
                                String gateVectorLiteral, int limit) {
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
                """, VECTOR_MAPPER, gateVectorLiteral, courseId, actorId, searchVectorLiteral, limit);
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

    // Phase 18.1 — the fuzzy lexical list: chunks containing a word spelled nearly like one of the
    // query's, ranked by how well and how many of them matched.
    //
    // **This is the half of hybrid retrieval a typo destroys, and it destroys it silently.**
    // `plainto_tsquery('english', 'recurssion')` produces the lexeme `recurss`, which is in no
    // chunk, so the sparse ranking for that question is not degraded — it is empty, and the fused
    // result is the dense list alone with nothing in the report to say so. `word_similarity` scores
    // 'recurssion' against 'recursion' at 0.75 and 'hashtable' against 'LinearHashTable' at 0.80,
    // measured on this corpus rather than assumed.
    //
    // The SQL is built rather than fixed because the number of terms varies, and the shape is the
    // one the planner can use: **one `<%` per term, OR-ed**, which becomes a BitmapOr over per-term
    // GIN index scans. `word_similarity(term, text) >= threshold` says the same thing and is a
    // sequential scan computing trigram sets for every chunk in the course, so the cut-off lives in
    // `pg_trgm.word_similarity_threshold` on the connection pool, beside `hnsw.iterative_scan` and
    // for the same reason: it is a property of how this schema is searched.
    //
    // Ranked by the *sum* over terms, not the best one: a chunk matching three of the question's
    // words should outrank a chunk matching one of them perfectly, which is the same judgement
    // `ts_rank` makes and the reason this list is fused with the lexical one rather than replacing
    // it. Both read `coalesce(embed_text, content)`, the expression `content_tsv` is generated from.
    List<ChunkHit> trigramSearch(UUID courseId, UUID actorId, List<String> terms, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        String matched = "coalesce(c.embed_text, c.content)";
        String score = terms.stream()
                .map(term -> "word_similarity(?, " + matched + ")")
                .collect(Collectors.joining(" + "));
        String filter = terms.stream()
                .map(term -> "? <% " + matched)
                .collect(Collectors.joining(" or "));

        // Terms twice — once for the score expression, once for the filter — then the scope, then
        // the limit. Positional parameters, so the order here is the order below.
        List<Object> args = new ArrayList<>(terms.size() * 2 + 3);
        args.addAll(terms);
        args.add(courseId);
        args.add(actorId);
        args.addAll(terms);
        args.add(limit);

        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality,
                       %s as trigram_score
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.modality = 'TEXT'
                  and (%s)
                order by trigram_score desc
                limit ?
                """.formatted(score, filter), TEXT_MAPPER, args.toArray());
    }

    // Every content word the question has, OR-ed instead of AND-ed (Phase 19.2).
    //
    // `plainto_tsquery` joins a question's words with `&`, so a chunk has to contain *all* of them.
    // On the fourteen-chapter evaluation corpus that is 1 chunk out of 282 for a typical question
    // and nothing at all for 46 of the 56 answerable ones — the lexical half of "hybrid" retrieval
    // has been silently empty for most questions since Phase 5, and the dense half has carried
    // them. This form takes `plainto_tsquery`'s own output — already parsed, stemmed and
    // stopword-stripped — and swaps its operators, which is a safe rewrite precisely because
    // `plainto_tsquery` emits nothing but `&`: no phrases, no negation, no weights.
    //
    // **Nothing is lost by relaxing the filter, because the filter was never what ranked.**
    // `ts_rank` already scores by how many of the query's lexemes a chunk carries and how often —
    // 0.041 for a passage matching five of six terms against 0.010 for one matching a single term,
    // measured on this database. AND-ing made that ranking almost unreachable: it threw away every
    // partial match before the ranking function saw it. The OR form matches 224 of 282 chunks and
    // then puts the right ones at the top, which is what a `limit 20` sparse retriever is for — and
    // it works: graded against the golden pages, sparse recall@6 goes from **0.116 to 0.884**, with
    // candidates returned for 56 questions out of 56 instead of 10.
    //
    // **It matters more for Bangla than for English, which is why it lands in this phase.** The
    // English stopword list is what saves the AND form on an English question: "what", "is", "the"
    // and "does" are dropped before the `&`s are inserted. That list is ASCII, so no Bangla
    // function word is in it — "কি", "কেন", "কীভাবে" all survive into the query and every one of
    // them has to appear in the chunk. A Bangla question therefore ANDs *more* terms than an
    // English one of the same length, including the ones carrying no meaning.
    //
    // Off by default all the same (`studyloop.retrieval.stages.lexical-or`): this changes what the
    // sparse half returns for every question in every language, so it moves every published
    // baseline, and 11.3's rule is that the switch and the run that justifies it are two events.
    private static final String ANY_TERM_TSQUERY =
            "replace(plainto_tsquery('english', ?)::text, ' & ', ' | ')::tsquery";

    // Lexical matches ranked by ts_rank over the generated content_tsv column (GIN-indexed).
    List<ChunkHit> fullTextSearch(UUID courseId, UUID actorId, String query, int limit) {
        return fullTextSearch(courseId, actorId, query, limit, false);
    }

    // `anyTerm` picks between the two query forms above. A parameter rather than a property read
    // here because this class is the one layer that must not have an opinion about pipeline
    // configuration — it is asked for a ranked list and says how one is obtained.
    List<ChunkHit> fullTextSearch(UUID courseId, UUID actorId, String query, int limit,
                                  boolean anyTerm) {
        String tsquery = anyTerm ? ANY_TERM_TSQUERY : "plainto_tsquery('english', ?)";
        // %1$s twice, one bound parameter each: the filter and the ranking function have to be
        // given the same query, and writing it once is what makes that structural rather than a
        // thing to remember. An empty tsquery — a question of nothing but stopwords — matches no
        // row under either form, which is the correct answer and not an error.
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)
                  and c.modality = 'TEXT'
                  and c.content_tsv @@ %1$s
                order by ts_rank(c.content_tsv, %1$s) desc
                limit ?
                """.formatted(tsquery), TEXT_MAPPER, courseId, actorId, query, query, limit);
    }
}
