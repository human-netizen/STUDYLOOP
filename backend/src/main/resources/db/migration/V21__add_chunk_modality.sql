-- Phase 17 — multimodal retrieval. One column, and the index split that column forces.

-- What a chunk's vector was made from.
--
-- Until now the answer was always "its text", so nothing had to say so. A visual chunk's vector is
-- an embedding of the *rendered page image*, produced by the same embed-v4.0 into the same
-- vector(768) space, and its `content` is the words that page can be answered from. So the two
-- kinds share a table, a column, an index type, a page number and a citation format, and differ
-- only in what was fed to the embedder.
--
-- Defaulted to TEXT and backfilled by that default, which is correct rather than convenient: every
-- row that exists when this runs was embedded from its text, and there is no row this could be
-- wrong about.
alter table document_chunks
    add column modality varchar(10) not null default 'TEXT';

comment on column document_chunks.modality is
    'TEXT = the vector is an embedding of embed_text; VISUAL = it is an embedding of the rendered '
        'page image, and content is the text that page is answered from (Phase 17.2).';

-- **Two partial indexes rather than one whole one, and this is the part that is not cosmetic.**
--
-- pgvector's HNSW index answers "nearest neighbours" and then the planner applies the WHERE clause
-- to what it returned. Both halves of hybrid search now ask for `modality = 'TEXT'`, so against a
-- single index over every row the scan would walk visual neighbours, discard them, and hand back
-- fewer than the twenty candidates the caller asked for — quietly, and worse the more figures a
-- course uploads. An index per modality means each search walks only rows it can use, so the
-- candidate count means what it says.
--
-- The text index is recreated rather than left alone for exactly that reason: leaving it whole
-- would make the text half's depth a function of how many pictures are in the corpus.
drop index if exists idx_document_chunks_embedding;

create index idx_document_chunks_embedding
    on document_chunks using hnsw (embedding vector_cosine_ops)
    where modality = 'TEXT';

create index idx_document_chunks_embedding_visual
    on document_chunks using hnsw (embedding vector_cosine_ops)
    where modality = 'VISUAL';
