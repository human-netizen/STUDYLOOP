-- Phase 29.2 — "you already have this lecture", reported and never enforced.
--
-- Deduplication today is exact: `unique (course_space_id, sha256)` (V4). That catches the same
-- file uploaded twice and nothing else. A `.pptx` and its PDF export are different bytes and the
-- same lecture; a course with two instructors reliably ends up with both. The corpus quietly
-- doubles, a question's candidates split across two copies, and the reader is shown two citations
-- to the same sentence.
--
-- **Two columns, not a table.** A document has at most one near-duplicate worth naming — the
-- closest one — and the alternative shape (a `document_similarities` join table holding every
-- pair above a floor) is a row count quadratic in the corpus for a report nobody reads past the
-- first line of.
--
-- `on delete set null` rather than cascade, and the distinction is V27's. A row recording
-- *something that happened* survives the thing it points at; this is not one of those. It is a
-- live pointer to a document that still exists, and once that document is gone "looks like a
-- lecture that is no longer here" is not a report, it is a dangling reference.
alter table documents
    add column near_duplicate_of uuid references documents (id) on delete set null,
    -- The share of this document's sampled chunks whose nearest neighbour anywhere else in the
    -- course sits above the per-chunk floor: 1.0 is "every passage of this is already in that
    -- one", which is what a re-export of the same lecture looks like.
    add column near_duplicate_score double precision;

comment on column documents.near_duplicate_of is
    'Phase 29.2: the existing course document this one most resembles, or null. Advisory only — '
    'nothing refuses an upload on the strength of it.';
