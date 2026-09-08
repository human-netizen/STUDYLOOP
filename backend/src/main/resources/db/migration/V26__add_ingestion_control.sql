-- Phase 25 — the three columns an ingest needs to be steerable, legible and survivable.
--
-- All three are on `documents` rather than in a new table, and that is the whole design decision.
-- A job table (V25's `video_jobs`) earns its keep when a run has a lifecycle the thing it produces
-- does not share — a render can be queued behind another render, retried, and deleted without
-- touching the video. An ingest has none of that: it runs once, it runs immediately, and its
-- lifecycle *is* the document's, which is why `status` and `error_message` have lived here since
-- V4. Adding a second row to keep in step with this one would buy nothing and introduce the class
-- of bug where the two disagree.

alter table documents
    -- The page range the pipeline was told to read, inclusive, 1-based, in the *source document's*
    -- own numbering. Null means the whole document, which is every row that existed before this
    -- migration and every upload that does not ask for a cut.
    --
    -- **The range is applied at extraction and never to the stored bytes.** The object in storage
    -- stays the whole PDF, so: the citation viewer opens the file the reader expects and lands on
    -- the page the citation names; a later re-ingest can choose a different range without a second
    -- upload; and the page numbers on every chunk stay absolute. Slicing the bytes and renumbering
    -- from one would shift every citation in the document by `first_page - 1` silently, and the
    -- symptom would be a viewer that opens a page not containing the sentence that was clicked.
    add column first_page integer,
    add column last_page  integer,

    -- How far through the pipeline this document is, 0-100, and the sentence to show beneath it.
    --
    -- Two columns rather than one, because they answer to different readers: the number drives a
    -- bar and must be comparable across documents, the sentence is written for a person and is
    -- never parsed. `video_jobs.stage` is the same column for the same reason.
    --
    -- `progress` is not derivable from `status`, which is why it is stored. EXTRACTING covers both
    -- "opened the file" and "reading page 210 of 296 with the vision model", and those are eight
    -- minutes apart on a scanned textbook — the gap this phase exists to close.
    add column progress integer not null default 0,
    add column stage    text,

    -- Pages whose vision call did not come back in time and which kept the text PDFBox extracted
    -- (Phase 25.3). Zero for everything, which is the truth for every row ingested before the
    -- timeout existed: without a timeout a slow page could not fall back, it could only hang.
    --
    -- **Counted rather than inferred.** A page that quietly kept its bad text is indistinguishable
    -- from a page that was fine — that is the argument PdfExtractionRouter makes for treating a
    -- vision failure as fatal — so degrading instead of failing is only defensible while something
    -- records that it happened. This column is that something, and the library row reads it.
    add column degraded_pages integer not null default 0;

comment on column documents.first_page is
    'Inclusive 1-based first page of the source document to ingest (Phase 25.1); null = from the '
        'start. Absolute: the stored bytes are never sliced, so citations keep the reader''s '
        'page numbers.';

comment on column documents.progress is
    'Ingestion progress 0-100 (Phase 25.2). Banded by status - EXTRACTING owns 5-55, CHUNKING '
        '55-70, EMBEDDING 70-98 - so the bar and the status badge cannot disagree. A FAILED '
        'document holds the value it reached rather than resetting: where it stopped is the most '
        'useful thing a failed row can say.';

comment on column documents.degraded_pages is
    'Pages that fell back to PDFBox text after the vision call passed its timeout (Phase 25.3). '
        'Non-zero means this document is indexed slightly worse than it could be, which is a '
        'statement the UI makes rather than one a reader has to infer from silence.';
