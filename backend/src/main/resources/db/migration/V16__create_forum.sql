-- Phase 9.2 — the escalation loop. A question the corpus could not answer becomes a thread the
-- class can answer, and an accepted answer is written back into the corpus so the next student
-- who asks it gets the answer from chat instead of another refusal.

create table forum_threads (
    id                 uuid        primary key,
    course_space_id    uuid        not null references course_spaces (id) on delete cascade,
    -- The refusal this thread came from, when it came from one. Null for a question someone
    -- simply chose to put to the class. `on delete set null`: the thread outlives the event.
    question_event_id  uuid        references question_events (id) on delete set null,
    title              text        not null,
    body               text,
    status             varchar(20) not null default 'OPEN',
    created_by         uuid        not null references users (id),
    accepted_answer_id uuid,
    -- The corpus document grown from the accepted answer. Null until that write succeeds, which
    -- is what lets the UI state "this is in the materials" as a fact rather than an assumption.
    answer_document_id uuid        references documents (id) on delete set null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create index idx_forum_threads_course on forum_threads (course_space_id, created_at desc);

-- One thread per refusal. Enforced here rather than by a check-then-insert in the service: two
-- students clicking "ask the class" on the same refused question is a race, and a partial unique
-- index settles it in the one place that can.
create unique index uq_forum_thread_question_event
    on forum_threads (question_event_id)
    where question_event_id is not null;

create table forum_answers (
    id         uuid        primary key,
    thread_id  uuid        not null references forum_threads (id) on delete cascade,
    body       text        not null,
    created_by uuid        not null references users (id),
    created_at timestamptz not null default now()
);

create index idx_forum_answers_thread on forum_answers (thread_id, created_at);

-- Added after forum_answers exists, since the two tables point at each other.
alter table forum_threads
    add constraint fk_forum_threads_accepted_answer
    foreign key (accepted_answer_id) references forum_answers (id) on delete set null;

-- Where a document's text came from. UPLOAD is every row that exists today; FORUM marks the ones
-- this feature grows from an accepted answer. They are real corpus documents — retrieval,
-- citations and the confidence gate treat them exactly like a lecture — but no file was ever
-- uploaded for them, which is why storage_path stops being mandatory.
alter table documents add column source varchar(20) not null default 'UPLOAD';
alter table documents alter column storage_path drop not null;
