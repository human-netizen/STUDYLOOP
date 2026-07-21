-- Full-text search half of hybrid retrieval (Phase 5.1). A generated tsvector column keeps
-- the lexeme index in lock-step with `content` (no trigger, no app code), and the GIN index
-- makes @@ / ts_rank queries fast. 'english' matches the query-side plainto_tsquery config.
alter table document_chunks
    add column content_tsv tsvector
        generated always as (to_tsvector('english', content)) stored;

create index idx_document_chunks_content_tsv
    on document_chunks using gin (content_tsv);
