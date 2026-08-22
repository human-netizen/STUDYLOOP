-- Phase 16.3 — handwritten notes as first-class documents, and the two things that requires.

-- 1. Who a document may answer.
--
-- Every document until now was course material: uploaded by a manager, or written by the system
-- from a forum answer a manager accepted. Both are curated, so "in this course" and "usable to
-- answer anyone in this course" were the same set and nothing had to say so. A photographed note
-- is neither — it is one member's notebook, and it can be wrong in ways a lecture handout is not.
--
-- Defaulted to COURSE and backfilled by that default, which is correct rather than convenient:
-- every row that exists when this runs *is* course material, and there is no row this could be
-- wrong about. New handwritten notes set OWNER explicitly.
alter table documents
    add column visibility varchar(20) not null default 'COURSE';

comment on column documents.visibility is
    'COURSE = part of the course corpus; OWNER = visible only to uploaded_by (Phase 16.3).';

-- Both halves of hybrid search now filter on (course, status, visibility-or-owner). The existing
-- index on the join covers the course; this one covers the rest, so the private-note check does
-- not turn every search into a scan of the course's documents.
create index if not exists idx_documents_course_visibility
    on documents (course_space_id, visibility, uploaded_by);

-- 2. What the vision model read, block by block, and how sure it said it was.
--
-- The rows that matter most here are the ones with `indexed = false`. Handwriting recognition is
-- wrong routinely rather than exceptionally, so a block the model half-guessed is not indexed —
-- but a dropped block nobody is shown is indistinguishable from a block that was never on the
-- page, and a silent gap in your own revision notes is the worst kind, because you revise from
-- what is there and never learn what is missing. These rows are what the review view reads.
--
-- Cascade delete: the blocks are the reading of one document and have no meaning without it.
create table document_note_blocks
(
    id          uuid             primary key,
    document_id uuid             not null references documents (id) on delete cascade,
    ordinal     integer          not null,
    content     text             not null,
    confidence  double precision not null,
    indexed     boolean          not null,
    constraint uq_note_block_document_ordinal unique (document_id, ordinal)
);

create index idx_note_blocks_document on document_note_blocks (document_id);

comment on table document_note_blocks is
    'Per-block transcription of a handwritten note, with the model''s confidence (Phase 16.3). '
        'Blocks with indexed = false were read but kept out of the corpus.';
