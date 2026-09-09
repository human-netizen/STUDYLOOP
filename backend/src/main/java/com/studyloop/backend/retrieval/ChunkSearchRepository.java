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
        return vectorSearch(courseId, actorId, searchVectorLiteral, gateVectorLiteral, limit,
                DocumentScope.WHOLE_COURSE);
    }

    List<ChunkHit> vectorSearch(UUID courseId, UUID actorId, String searchVectorLiteral,
                                String gateVectorLiteral, int limit, DocumentScope scope) {
        List<Object> args = new ArrayList<>();
        args.add(gateVectorLiteral);
        args.add(courseId);
        args.add(actorId);
        args.addAll(scope.documentIds());
        args.add(searchVectorLiteral);
        args.add(limit);
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality,
                       1 - (c.embedding <=> cast(? as vector)) as cosine_similarity
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)%s
                  and c.modality = 'TEXT'
                  and c.embedding is not null
                order by c.embedding <=> cast(? as vector)
                limit ?
                """.formatted(scopeClause(scope)), VECTOR_MAPPER, args.toArray());
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

    // The same read for several sections at once (Phase 26.2).
    //
    // **Six retrieved chunks in four sections was four round trips, and on a WAN that is the whole
    // cost.** Each query was already indexed and already fast; what made it slow was that there
    // were four of them, one after another, each paying the same ~40ms to Tokyo and back. One
    // row-wise `in` returns all four sections in one trip, and the grouping the caller needs is
    // the grouping it was already doing in a HashMap.
    //
    // Ordered by the same `chunk_index` as the single-section form, with the two key columns ahead
    // of it so a section's chunks arrive contiguously and in document order - which is what
    // small-to-big expansion walks outward through.
    List<SectionRow> sectionChunks(List<SectionKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        String pairs = keys.stream()
                .map(key -> "(cast(? as uuid), cast(? as text))")
                .collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(keys.size() * 2);
        for (SectionKey key : keys) {
            args.add(key.documentId());
            args.add(key.sectionPath());
        }
        return jdbc.query("""
                select document_id, section_path, id, content
                from document_chunks
                where (document_id, section_path) in (%s)
                order by document_id, section_path, chunk_index
                """.formatted(pairs), SECTION_ROW_MAPPER, args.toArray());
    }

    private static final RowMapper<SectionRow> SECTION_ROW_MAPPER = (rs, row) -> new SectionRow(
            UUID.fromString(rs.getString("document_id")),
            rs.getString("section_path"),
            UUID.fromString(rs.getString("id")),
            rs.getString("content"));

    // Which section: one document, one path. A record rather than a concatenated string key so the
    // pair that goes into the SQL and the pair that groups the results are the same object.
    record SectionKey(UUID documentId, String sectionPath) { }

    record SectionRow(UUID documentId, String sectionPath, UUID id, String content) { }

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
        return trigramSearch(courseId, actorId, terms, limit, DocumentScope.WHOLE_COURSE);
    }

    List<ChunkHit> trigramSearch(UUID courseId, UUID actorId, List<String> terms, int limit,
                                 DocumentScope scope) {
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
        // the limit. Positional parameters, so the order here is the order below. 28.2's document
        // scope binds between the actor and the filter's copy of the terms, which is where the
        // clause sits in every branch in this class.
        List<Object> args = new ArrayList<>(terms.size() * 2 + scope.size() + 3);
        args.addAll(terms);
        args.add(courseId);
        args.add(actorId);
        args.addAll(scope.documentIds());
        args.addAll(terms);
        args.add(limit);

        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality,
                       %1$s as trigram_score
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)%3$s
                  and c.modality = 'TEXT'
                  and (%2$s)
                order by trigram_score desc
                limit ?
                """.formatted(score, filter, scopeClause(scope)), TEXT_MAPPER, args.toArray());
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
        return fullTextSearch(courseId, actorId, query, limit, anyTerm, DocumentScope.WHOLE_COURSE);
    }

    List<ChunkHit> fullTextSearch(UUID courseId, UUID actorId, String query, int limit,
                                  boolean anyTerm, DocumentScope scope) {
        String tsquery = anyTerm ? ANY_TERM_TSQUERY : "plainto_tsquery('english', ?)";
        // %1$s twice, one bound parameter each: the filter and the ranking function have to be
        // given the same query, and writing it once is what makes that structural rather than a
        // thing to remember. An empty tsquery — a question of nothing but stopwords — matches no
        // row under either form, which is the correct answer and not an error.
        List<Object> args = new ArrayList<>();
        args.add(courseId);
        args.add(actorId);
        args.addAll(scope.documentIds());
        args.add(query);
        args.add(query);
        args.add(limit);
        return jdbc.query("""
                select c.id, c.document_id, d.filename, d.source, c.page_number, c.page_end,
                       c.section_path, c.content, c.token_count, c.modality
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)%2$s
                  and c.modality = 'TEXT'
                  and c.content_tsv @@ %1$s
                order by ts_rank(c.content_tsv, %1$s) desc
                limit ?
                """.formatted(tsquery, scopeClause(scope)), TEXT_MAPPER, args.toArray());
    }

    // Phase 28.2 — the one predicate that narrows a search to documents the reader chose.
    //
    // **It is absent from the SQL entirely when the scope is the whole course**, which is what
    // makes the feature free for every caller that does not use it: no predicate, no plan change,
    // no eval number that moves. The parameters are bound positionally and this clause sits
    // immediately after the visibility clause in every branch, so the argument order is the same
    // everywhere — course, actor, scope, then whatever that branch searches with.
    //
    // Written as a row-wise `in` over placeholders rather than an array parameter, which is the
    // technique 26.2 used for `sectionChunks`: one plan per distinct id count, all of them
    // index-usable, and no dependence on the driver's array support.
    private static String scopeClause(DocumentScope scope) {
        if (scope.isWholeCourse()) {
            return "";
        }
        String placeholders = scope.documentIds().stream()
                .map(id -> "cast(? as uuid)")
                .collect(Collectors.joining(", "));
        return "\n                  and c.document_id in (" + placeholders + ")";
    }

    // Phase 26.2 - the three candidate lists in one round trip instead of three.
    //
    // **This is a transport change, not a fusion change, and the distinction is the whole point.**
    // Reciprocal Rank Fusion reads a chunk's *position within its own list*; three lists merged
    // into one ranking is a different algorithm with the same name, and it would move every
    // Recall@6, MRR and nDCG this project has published without failing anything. So each branch
    // keeps its own `order by` and its own `limit`, numbers its own rows, and the service splits
    // them back apart by the `list` column. What is saved is two network round trips to Tokyo;
    // what is returned is byte-for-byte the three lists the three queries returned, which is what
    // the equivalence test in the suite exists to keep true.
    //
    // `row_number() over ()` is deliberately given no ordering of its own. Each branch is already
    // an `order by ... limit` subquery - a barrier Postgres materialises in order - so numbering
    // the rows in input order is what preserves the database's own tie-breaking. A window with its
    // own `order by` would re-sort ties and could hand back a different order from the one the
    // limit selected.
    //
    // The trigram and HyDE lists stay out of it, and that is not an oversight: both are
    // conditional and both are off, and folding a conditional query into a mandatory statement is
    // how a switched-off stage starts costing money.
    Candidates candidateSearch(UUID courseId, UUID actorId, String queryVectorLiteral, String query,
                               int limit, boolean anyTerm, boolean includeVisual) {
        return candidateSearch(courseId, actorId, queryVectorLiteral, query, limit, anyTerm,
                includeVisual, DocumentScope.WHOLE_COURSE);
    }

    // Phase 28.2 — the same union, narrowed to the documents the reader chose.
    //
    // **One predicate in the two branch templates, and the shape of the statement does not
    // change.** Each branch keeps its own `order by` and its own `limit`, the `union all` is the
    // same `union all`, and the fusion above is handed the same three lists it was handed before —
    // shorter, and drawn from fewer documents. Narrowing what a ranked list is drawn from is not
    // the same kind of change as merging two of them, which is why this one is free and 26.2 had
    // to argue for itself.
    Candidates candidateSearch(UUID courseId, UUID actorId, String queryVectorLiteral, String query,
                               int limit, boolean anyTerm, boolean includeVisual,
                               DocumentScope scope) {
        String tsquery = anyTerm ? ANY_TERM_TSQUERY : "plainto_tsquery('english', ?)";
        String scoped = scopeClause(scope);
        List<String> branches = new ArrayList<>(3);
        List<Object> args = new ArrayList<>();

        if (queryVectorLiteral != null) {
            branches.add(denseBranch("VECTOR", "TEXT", scoped));
            args.add(queryVectorLiteral);
            args.add(courseId);
            args.add(actorId);
            args.addAll(scope.documentIds());
            args.add(queryVectorLiteral);
            args.add(limit);
        }
        branches.add(LEXICAL_BRANCH.formatted(tsquery, tsquery, scoped));
        args.add(courseId);
        args.add(actorId);
        args.addAll(scope.documentIds());
        args.add(query);
        args.add(query);
        args.add(limit);
        if (includeVisual && queryVectorLiteral != null) {
            branches.add(denseBranch("VISUAL", "VISUAL", scoped));
            args.add(queryVectorLiteral);
            args.add(courseId);
            args.add(actorId);
            args.addAll(scope.documentIds());
            args.add(queryVectorLiteral);
            args.add(limit);
        }

        // Ordered by (list, rank) so the rows arrive grouped and in each list's own order, which
        // makes the split below an append rather than a sort.
        String sql = String.join("\nunion all\n", branches) + "\norder by 1, 2";
        List<Listed> rows = jdbc.query(sql, LISTED_MAPPER, args.toArray());

        List<ChunkHit> vector = new ArrayList<>();
        List<ChunkHit> text = new ArrayList<>();
        List<ChunkHit> visual = new ArrayList<>();
        for (Listed row : rows) {
            switch (row.list()) {
                case "VECTOR" -> vector.add(row.hit());
                case "TEXT" -> text.add(row.hit());
                case "VISUAL" -> visual.add(row.hit());
                default -> { }
            }
        }
        return new Candidates(vector, text, visual);
    }

    // One dense branch: the same query for text chunks and for page images, differing in the
    // modality predicate and in nothing else - the same column, the same HNSW index, the same
    // course scope, the same READY filter, the same visibility clause, the same citation fields.
    private static String denseBranch(String list, String modality, String scoped) {
        return """
                select cast('%1$s' as text) as list, row_number() over () as rn, d.*
                from (
                  select c.id, c.document_id, doc.filename, doc.source, c.page_number, c.page_end,
                         c.section_path, c.content, c.token_count, c.modality,
                         1 - (c.embedding <=> cast(? as vector)) as cosine_similarity
                  from document_chunks c
                  join documents doc on doc.id = c.document_id
                  where doc.course_space_id = ?
                    and doc.status = 'READY'
                    and (doc.visibility = 'COURSE' or doc.uploaded_by = ?)%3$s
                    and c.modality = '%2$s'
                    and c.embedding is not null
                  order by c.embedding <=> cast(? as vector)
                  limit ?
                ) d""".formatted(list, modality, scoped);
    }

    // The lexical branch, with no similarity of its own to report - `cast(null as double
    // precision)` is what keeps the three branches union-compatible, and it says the same thing
    // the separate query said by leaving the field null.
    private static final String LEXICAL_BRANCH = """
            select cast('TEXT' as text) as list, row_number() over () as rn, d.*
            from (
              select c.id, c.document_id, doc.filename, doc.source, c.page_number, c.page_end,
                     c.section_path, c.content, c.token_count, c.modality,
                     cast(null as double precision) as cosine_similarity
              from document_chunks c
              join documents doc on doc.id = c.document_id
              where doc.course_space_id = ?
                and doc.status = 'READY'
                and (doc.visibility = 'COURSE' or doc.uploaded_by = ?)%3$s
                and c.modality = 'TEXT'
                and c.content_tsv @@ %1$s
              order by ts_rank(c.content_tsv, %2$s) desc
              limit ?
            ) d""";

    private static final RowMapper<Listed> LISTED_MAPPER = (rs, row) -> new Listed(
            rs.getString("list"), VECTOR_MAPPER.mapRow(rs, row));

    private record Listed(String list, ChunkHit hit) { }

    // The three rankings, still three. Named rather than returned as a list of lists so a caller
    // cannot mix up which is which - the confidence gate reads the vector list's head and the
    // lexical list's size, and those two are not interchangeable.
    record Candidates(List<ChunkHit> vector, List<ChunkHit> text, List<ChunkHit> visual) { }
}
