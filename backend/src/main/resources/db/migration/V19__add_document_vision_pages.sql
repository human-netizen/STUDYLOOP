-- Phase 15 — how many of a document's pages a vision model had to read.
--
-- One column, no backfill, no index. Written on every ingest from here on; null on every row
-- ingested before this phase, which is exactly what those rows mean — nobody scored their pages,
-- so "0 pages needed the model" would be a claim nothing measured.
--
-- **Why this is stored at all, when it is only a number in a log line.** The eval report has to be
-- able to say which pipeline produced the corpus it is quoting, and Phase 14 proved that reading
-- that off the configuration does not work: a flag says what was asked for on the day the report
-- ran, not what was true on the day the corpus was ingested. Phase 14 had to recover the fact by
-- counting a marker string inside embed_text. Recording it directly is the same instrument without
-- the archaeology — and unlike the marker, it survives a change to how the text is written.
alter table documents add column vision_pages integer;

comment on column documents.vision_pages is
    'Pages routed to the VLM extractor at ingest (Phase 15). Null for documents ingested before the router existed.';
