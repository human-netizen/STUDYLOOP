-- Phase 19.1 — what language a document is written in, decided at ingest.

-- One column on `documents`, and deliberately not one on `document_chunks`.
--
-- 19.2's plan was to make `content_tsv` per-language, which would have needed the language
-- denormalized onto every chunk so a generated column could read it. That is buildable — Postgres
-- accepts `to_tsvector(case when lang = 'BANGLA' then 'simple'::regconfig else 'english'::regconfig
-- end, body)` in a stored generated column, which was checked against this database before any of
-- this was written — and measuring it first showed there is nothing for it to do. On Bangla text
-- `to_tsvector('english', …)` and `to_tsvector('simple', …)` produce *byte-identical* tsvectors,
-- because the English stemmer and the English stopword list are both ASCII rules that no Bengali
-- word can trigger. The config is already a no-op for Bangla; switching it would only stop the
-- English technical vocabulary a Bangla CS text is full of from being stemmed. So the column that
-- would have driven it is not here, and the reason is in DEVIATIONS.md.
--
-- What is left for the language to drive is real but smaller: which sentence terminator the chunker
-- looks for, which language the answer is written in, and keeping the Bangla half of the evaluation
-- set reported apart from the English half.
alter table documents
    add column language varchar(20) not null default 'ENGLISH';

comment on column documents.language is
    'Script-detected at ingest (Phase 19.1). ENGLISH or BANGLA — two values because the detector '
        'distinguishes two scripts, and a column that accepted 180 ISO codes would be claiming a '
        'precision nothing in this application can produce.';

-- Backfilled by the default, which is a claim about the corpus and not a convenience: every
-- document ingested before this migration went through an English-only pipeline, and the fourteen
-- chapters in the evaluation corpus are English. There is no row this default is wrong about
-- today; a Bangla document uploaded after it is detected on the way in.
