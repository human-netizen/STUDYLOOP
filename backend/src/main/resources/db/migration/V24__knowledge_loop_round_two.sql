-- Phase 20 — the knowledge loop, round two.
--
-- Round one (Phase 9.2) ran in one direction: a refusal became a thread, a person answered it,
-- and a manager accepted the answer into the corpus. Everything here closes the other direction.
-- A document arriving can make an open thread answerable, and a refusal can be escalated to
-- general knowledge instead of to the class. Both are recorded, because both are measurements of
-- the same thing: what the materials do not cover.

-- ── 20.1 · a reply the assistant wrote ──────────────────────────────────────────────────────
--
-- forum_answers has held exactly one kind of row until now — a person's reply — and created_by
-- was not null because there was always a person. The corpus watch adds a second kind, so the
-- author becomes two columns: *what* wrote it, and *who*, with the second one null for the
-- machine. A sentinel user row would have been less schema and a worse idea: it would put a fake
-- member in a course's user list and make every existing join silently attribute model output to
-- somebody.
alter table forum_answers add column author_kind varchar(20) not null default 'MEMBER';

alter table forum_answers alter column created_by drop not null;

-- A person's reply must still have a person behind it. Without this the nullable column above
-- would quietly allow an anonymous member answer, which is exactly the row the accept path
-- assumes cannot exist.
alter table forum_answers
    add constraint ck_forum_answers_author
    check (author_kind <> 'MEMBER' or created_by is not null);

-- Which upload made this thread answerable. Provenance, and the whole demonstration: the thread
-- names the document that arrived, so "refused on Tuesday, answered when Lecture 07 landed" is a
-- fact the page can state rather than a story told over it.
alter table forum_answers
    add column source_document_id uuid references documents (id) on delete set null;

-- The confidence signal behind the machine's reply, kept for the comparison rather than for the
-- decision — the gate already made that. It is deliberately the same measurement
-- question_events.top_similarity holds (the best question-to-chunk cosine), so the pair
-- "refused at 0.19 / answered at 0.63" is two readings of one instrument. Storing the rerank
-- relevance here instead would have been a truer record of what the gate read and a useless
-- number to put beside the refusal, because the two live on different scales.
alter table forum_answers add column top_similarity double precision;

-- One machine reply per thread, ever. The sweep runs on every upload, and without this a course
-- importing a semester of lectures would answer the same open thread eleven times. The bound is
-- per thread rather than per (thread, document) on purpose: a second machine answer says nothing
-- the first one did not, and the thread's own status is what changes when a person joins in.
create unique index uq_forum_answers_one_assistant
    on forum_answers (thread_id)
    where author_kind = 'ASSISTANT';

-- ── 20.2 · the general-knowledge escape hatch ───────────────────────────────────────────────
--
-- Stamped on the refusal itself rather than written as a second event row. The alternative was
-- one more question_events row per escalation, which would have inflated "questions asked" —
-- the denominator of every rate on the instructor page — with clicks rather than questions.
-- Null means nobody escalated this refusal, which is the common case.
alter table question_events add column escalated_at timestamptz;
