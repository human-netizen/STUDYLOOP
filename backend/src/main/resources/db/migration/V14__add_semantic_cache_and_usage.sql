-- Phase 8.3 — two halves of the same story, so one migration.
--
-- chat_cache_entries is the semantic answer cache: the *question's* embedding is the key, so a
-- question that merely means the same thing as an earlier one hits it. Scoped per course because
-- the same words answered against different materials are different answers.
--
-- ai_usage_events is the ledger behind /admin/costs: one row per provider call actually made.
-- Without it "the cache saves money" is a claim; with it, it's a number.

create table chat_cache_entries (
    id                 uuid          primary key,
    course_space_id    uuid          not null references course_spaces (id) on delete cascade,
    question           text          not null,
    -- Unmapped in JPA, like document_chunks.embedding — Hibernate has no vector type, so this
    -- column is written and searched with native SQL and a text-literal cast.
    question_embedding vector(768)   not null,
    answer             text          not null,
    -- The sources the cached answer cites, stored verbatim so a hit renders exactly like a fresh
    -- answer without re-running retrieval.
    citations          jsonb         not null default '[]'::jsonb,
    hit_count          int           not null default 0,
    created_at         timestamptz   not null default now(),
    last_hit_at        timestamptz
);

-- Invalidation deletes a whole course's entries at once (its corpus changed), so course_space_id
-- leads its own index rather than riding along on the vector one.
create index idx_chat_cache_course on chat_cache_entries (course_space_id);

-- Same HNSW/cosine setup as document_chunks: the lookup is "nearest neighbour, then check it
-- cleared the threshold", which is exactly what this index answers.
create index idx_chat_cache_embedding
    on chat_cache_entries using hnsw (question_embedding vector_cosine_ops);

create table ai_usage_events (
    id            uuid          primary key,
    occurred_at   timestamptz   not null default now(),
    -- Who was billed ("cohere"), which model, and which feature asked. The feature matters most:
    -- a total spend figure can't tell you that quiz generation costs ten times what chat does.
    provider      varchar(40)   not null,
    model         varchar(120)  not null,
    operation     varchar(40)   not null,
    input_tokens  int           not null default 0,
    output_tokens int           not null default 0,
    -- Priced at write time from studyloop.pricing. Storing the money rather than recomputing it
    -- means a later price change doesn't silently rewrite history.
    cost_usd      numeric(12, 6) not null default 0
);

-- Every dashboard query is "recent activity", either windowed or grouped by day.
create index idx_ai_usage_occurred_at on ai_usage_events (occurred_at desc);
