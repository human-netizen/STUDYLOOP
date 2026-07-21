-- RAG chat (Phase 5.2). A conversation groups a back-and-forth thread inside one course;
-- messages store the turns so follow-up questions can carry prior context to the model.
create table chat_conversations (
    id              uuid         primary key,
    course_space_id uuid         not null references course_spaces (id) on delete cascade,
    created_by      uuid         not null references users (id),
    title           varchar(200),
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);

create index idx_chat_conversations_course on chat_conversations (course_space_id);

create table chat_messages (
    id              uuid         primary key,
    conversation_id uuid         not null references chat_conversations (id) on delete cascade,
    role            varchar(16)  not null,  -- USER | ASSISTANT
    content         text         not null,
    created_at      timestamptz  not null default now()
);

create index idx_chat_messages_conversation on chat_messages (conversation_id, created_at);
