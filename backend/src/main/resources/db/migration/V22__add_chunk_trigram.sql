-- Phase 18.1 — trigram matching, so a misspelled word still finds its page.

-- The lexical half of hybrid retrieval is a lexeme index: `plainto_tsquery('english', 'recurssion')`
-- produces the lexeme `recurss`, which matches nothing, and the whole sparse ranking for that
-- question is empty. The dense half degrades gracefully under a typo — a subword tokenizer still
-- produces a vector near the right one — so the failure is invisible in the averages and total
-- for the half that fails. Trigram similarity is the standard fix, it is a Postgres extension
-- rather than a provider, and it costs no API call.
create extension if not exists pg_trgm;

-- Indexed over `coalesce(embed_text, content)`, the same expression `content_tsv` is generated
-- from (V18). Two sparse retrievers reading different text would make their disagreement a
-- property of which column each happened to be built on rather than of how each matches, and the
-- context header this project prepends at embed time is exactly the kind of short, high-signal
-- phrase — "Skiplists › Analysis" — a typo'd query most needs to still reach.
--
-- A trigram GIN index stores every three-character window of every chunk, so it is the largest
-- index on this table: roughly the size of the text itself. That is the cost of the feature and it
-- is paid at ingest, not per question.
--
-- Partial on TEXT for the same reason V21 split the vector indexes: this list is a *lexical*
-- retriever, and a visual chunk's `content` is a copy of its page's text that the two text halves
-- already deliberately exclude. Without the predicate a figure page would enter the candidate set
-- twice, once as a picture and once as a smaller copy of a page the text halves also return.
create index idx_document_chunks_content_trgm
    on document_chunks using gin ((coalesce(embed_text, content)) gin_trgm_ops)
    where modality = 'TEXT';

comment on index idx_document_chunks_content_trgm is
    'Trigram index behind the Phase 18.1 fuzzy lexical list. Queried with the <% (word_similarity) '
        'operator, whose cut-off is pg_trgm.word_similarity_threshold, set on the connection pool.';
