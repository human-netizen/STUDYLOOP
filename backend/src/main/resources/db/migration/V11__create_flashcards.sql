-- Flashcards (Phase 7.3). A personal study card owned by the member who created it: either
-- generated from a document or saved from a chat Q&A exchange. source_document_id/source_page
-- are optional provenance (kept when generated from a document); on document delete they null out
-- so the card survives. These feed the SM-2 review queue in Phase 8.
create table flashcards (
    id                 uuid        primary key,
    course_space_id    uuid        not null references course_spaces (id) on delete cascade,
    created_by         uuid        not null references users (id),
    front              text        not null,
    back               text        not null,
    source_document_id uuid        references documents (id) on delete set null,
    source_page        int,
    created_at         timestamptz not null default now()
);

create index idx_flashcards_course_user on flashcards (course_space_id, created_by, created_at desc);
