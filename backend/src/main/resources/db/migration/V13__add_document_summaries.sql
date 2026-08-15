-- Per-document summary and glossary (Phase 8.2). The model call is the expensive part, so the
-- result is generated once — right after ingestion reaches READY — and cached here rather than
-- recomputed on every page view.
--
-- Both stay null/empty when generation hasn't run or failed: summarization is deliberately
-- best-effort and happens AFTER the document is READY, so a provider outage leaves the document
-- fully usable for chat, quizzes and flashcards instead of marking it FAILED.
alter table documents
    add column summary              text,
    add column summary_generated_at timestamptz;

-- Key terms the model lifted from the document, in the order it returned them (term_index),
-- which is roughly order of importance.
create table document_terms (
    id          uuid         primary key,
    document_id uuid         not null references documents (id) on delete cascade,
    term_index  int          not null,
    term        varchar(120) not null,
    definition  text         not null
);

-- Keeps regeneration a clean replace and doubles as the lookup index for "this document's
-- glossary", since document_id leads the key.
create unique index uq_document_terms_doc_index on document_terms (document_id, term_index);
