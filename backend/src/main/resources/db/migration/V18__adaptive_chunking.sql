-- Phase 13 — adaptive chunking. Three columns and one rebuilt index.
--
-- Nothing here is backfilled. Chunks written by the old sliding window keep a null embed_text, a
-- null section_path and a null page_end, and every read below is written to fall back to what they
-- do have. Re-ingesting a document replaces its chunks in place (uq_chunk_document_index), so a
-- corpus upgrades a document at a time rather than in one migration that would have to re-embed
-- every row it touched.

-- What gets embedded and lexically indexed, as opposed to what gets displayed and cited. The
-- context header lives here: the document's title and heading path in front of the passage.
alter table document_chunks add column embed_text text;

-- "Chapter 4 > 4.2 Skiplists" — the heading trail this chunk sits under. Read at query time to
-- expand a retrieved chunk to its whole section before the prompt is built (13.5).
alter table document_chunks add column section_path text;

-- The last page the chunk's text runs onto; page_number remains the first. A chunk used to record
-- only where it began, which is why the eval had to grade with a ±1 page tolerance.
alter table document_chunks add column page_end integer;

-- content_tsv was generated from `content`. It has to be generated from the embedded text instead,
-- or the lexical half of retrieval never sees the context header — and Phase 14's synthetic
-- queries, which are keyword-dense paraphrases and exactly what a GIN-indexed tsvector is good at,
-- would reach the dense half only. A generated column's expression cannot be altered in place, so
-- it is dropped and rebuilt; on a corpus this size that is a few seconds of recompute.
--
-- coalesce is what makes the migration safe on old rows: no embed_text means index the content,
-- which is precisely what this column did yesterday.
drop index if exists idx_document_chunks_content_tsv;
alter table document_chunks drop column content_tsv;

alter table document_chunks
    add column content_tsv tsvector
        generated always as (to_tsvector('english', coalesce(embed_text, content))) stored;

create index idx_document_chunks_content_tsv
    on document_chunks using gin (content_tsv);

-- Answers "the rest of the section this chunk belongs to" without a sequential scan of the
-- document. One lookup per retrieved chunk happens on the request path, six times a question.
create index idx_document_chunks_section
    on document_chunks (document_id, section_path);
