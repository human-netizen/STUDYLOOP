-- Phase 27 — the schema half of giving this application a delete verb.
--
-- Nothing here adds a feature. Every statement below fixes a foreign key that was written when
-- deleting a document was impossible, and which becomes a history-rewriting delete the moment it
-- is not. `on delete cascade` was the correct clause in V15 and V25: the only thing that could
-- ever remove a document was a course being dropped, and in that case the analytics and the
-- videos were going with it. A per-document delete button changes what that clause means without
-- changing a character of it.
--
-- The rule this migration applies: **a row that records something that happened keeps existing
-- after the thing it points at is gone.** A question was asked. A video was rendered and watched.
-- Deleting the lecture afterwards does not un-ask the question, and a schema that says it does is
-- a schema that lies to the instructor's page.

-- ── 1. question_event_documents: the confusion heatmap stops rewriting history ────────────────
--
-- `document_id` cascaded, so deleting a lecture deleted the rows attributing questions to it —
-- while `question_events.grounded` stayed true. The totals on the instructor's page (asked,
-- ungrounded, askers) come from question_events and the per-lecture heat comes from this table,
-- so after one delete the two stopped reconciling and nothing said why.
--
-- The filename is carried rather than joined, which is the whole point: after the delete there is
-- no row left to join to. A snapshot of a name is not denormalisation for speed, it is the only
-- surviving record that the question landed on *something*.
alter table question_event_documents
    add column document_filename varchar(255);

update question_event_documents ed
set document_filename = d.filename
from documents d
where d.id = ed.document_id;

-- The primary key contained document_id, and a nullable column cannot sit in a primary key. It is
-- replaced by a partial unique index over the live rows only. Two deleted lectures behind one
-- question are then two rows with a null document_id and different filenames — which is the truth,
-- and which a unique constraint over the pair would have collapsed into one.
alter table question_event_documents
    drop constraint question_event_documents_pkey,
    drop constraint question_event_documents_document_id_fkey,
    alter column document_id drop not null,
    add constraint question_event_documents_document_id_fkey
        foreign key (document_id) references documents (id) on delete set null;

create unique index uq_question_event_documents_live
    on question_event_documents (question_event_id, document_id)
    where document_id is not null;

comment on column question_event_documents.document_filename is
    'The lecture''s name as it was when the question was asked (Phase 27.3). Survives the '
        'document''s deletion, which is when document_id goes null - so the heatmap can say '
        '"a document that no longer exists" instead of quietly losing the attribution.';

-- ── 2. video_scene_citations: a rendered video stays citable ──────────────────────────────────
--
-- A finished video is an artifact that was correct when it was made. Its scenes each name the
-- passage they were written from, and that promise — "every scene carries its citation" — was
-- enforced before a frame was rendered. Cascading from document_chunks meant deleting the source
-- silently downgraded every video built from it to an uncited one, while the video went on
-- playing exactly as before.
--
-- The snapshot is what the rail actually displays: the filename, the page, and the passage. The
-- chunk_id stays as the live link, and is what the click-through to the PDF needs — so a citation
-- whose document still exists opens, and one whose document is gone still reads.
alter table video_scene_citations
    add column document_filename varchar(255),
    add column page_number       integer,
    add column passage           text;

update video_scene_citations vsc
set document_filename = d.filename,
    page_number       = c.page_number,
    passage           = left(c.content, 240)
from document_chunks c
         join documents d on d.id = c.document_id
where c.id = vsc.chunk_id;

alter table video_scene_citations
    drop constraint video_scene_citations_pkey,
    drop constraint video_scene_citations_chunk_id_fkey,
    alter column chunk_id drop not null,
    add constraint video_scene_citations_chunk_id_fkey
        foreign key (chunk_id) references document_chunks (id) on delete set null;

-- Same shape as above, and for the same reason: two deleted sources cited by one scene are two
-- citations. The ordinal is what orders them either way.
create unique index uq_video_scene_citations_live
    on video_scene_citations (scene_id, chunk_id)
    where chunk_id is not null;

create index idx_video_scene_citations_scene on video_scene_citations (scene_id, ordinal);

comment on column video_scene_citations.passage is
    'The cited text as the scene was written from it (Phase 27.3), truncated to the length the '
        'rail renders. Read when chunk_id has gone null because the document was deleted; the '
        'live join is still preferred while the chunk exists, so an edited corpus is not shown '
        'stale text.';

-- ── 3. course_spaces.archived_at: the same idea as retiring a document ────────────────────────
--
-- A course that finished in June should be able to leave the list without anybody having to
-- decide whether to destroy a semester of material. Nullable timestamp rather than a boolean,
-- because *when* is free to store and answers the only question a boolean would raise next.
alter table course_spaces
    add column archived_at timestamptz;

comment on column course_spaces.archived_at is
    'When this course was archived (Phase 27.4); null means active. Archiving hides the course '
        'from the list and nothing else - every document, thread and quiz is untouched, and '
        'un-archiving is one update.';
