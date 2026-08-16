-- Phase 9.1 — confusion analytics.
--
-- Chat already knows, for every question, whether it could ground an answer and which documents
-- it grounded on. Until now it threw all of that away the moment the response was sent. These
-- tables keep it, so an instructor can see what the class is actually stuck on rather than
-- guessing from the two students who spoke up in the lecture.

create table question_events (
    id                 uuid          primary key,
    course_space_id    uuid          not null references course_spaces (id) on delete cascade,
    -- Kept so the heatmap can count *students* and not just questions — one person asking the
    -- same thing eight times is a different signal from eight people asking it once. Never
    -- leaves the server: the API exposes counts, never identities. That is what makes this
    -- "anonymized analytics" rather than a surveillance log of who is struggling.
    asked_by           uuid          not null references users (id),
    question           text          not null,
    -- Unmapped in JPA, like every other vector column here. Nullable on purpose: a question
    -- asked while no embedding provider is configured still counts toward the totals, it just
    -- cannot be clustered with anything.
    question_embedding vector(768),
    -- False when the confidence gate refused to answer. These are the interesting rows — a
    -- question the corpus could not answer is either a gap in the materials or a gap in the
    -- retrieval, and both are worth an instructor's attention.
    grounded           boolean       not null,
    -- The best cosine similarity retrieval found, for ranking "nearly answerable" against
    -- "nowhere near". Null when no vector search ran.
    top_similarity     double precision,
    created_at         timestamptz   not null default now()
);

-- Every read is "this course, recently", either for the heatmap or to gather vectors to cluster.
create index idx_question_events_course on question_events (course_space_id, created_at desc);

-- Deliberately *no* HNSW index on question_embedding, unlike document_chunks and
-- chat_cache_entries. Nothing here does nearest-neighbour lookups: clustering pulls a course's
-- vectors out in one query and groups them in memory. An index nobody queries would still be
-- rebuilt on every insert — and inserts here sit on the chat request path.

-- Which lectures a question actually landed on: the documents behind the chunks that grounded
-- the answer, deduped. One question routinely touches two or three, which is why this is a
-- table and not a column. Only grounded questions get rows — see QuestionLogService for why
-- attributing a refusal to the lecture it *almost* matched would be a lie.
create table question_event_documents (
    question_event_id uuid not null references question_events (id) on delete cascade,
    document_id       uuid not null references documents (id) on delete cascade,
    primary key (question_event_id, document_id)
);

create index idx_question_event_documents_doc on question_event_documents (document_id);

-- Clustering output, recomputed by a scheduled job rather than derived per request. Grouping is
-- the one genuinely expensive part of this feature, and its input changes at the speed students
-- type — minutes, not milliseconds. Caching it means the instructor's page is a plain select.
create table question_clusters (
    id               uuid          primary key,
    course_space_id  uuid          not null references course_spaces (id) on delete cascade,
    -- The medoid question: the real student wording closest to the cluster's centre. An LLM
    -- could write a tidier topic name, but that is a provider call per cluster per recompute,
    -- and a verbatim question tells an instructor more than "Recursion concepts" would.
    label            text          not null,
    question_count   int           not null,
    ungrounded_count int           not null,
    distinct_askers  int           not null,
    -- {documentId: questionCount} for the grounded members. jsonb rather than a child table:
    -- it is written and read as one blob, never joined or filtered on.
    document_counts  jsonb         not null default '{}'::jsonb,
    last_asked_at    timestamptz   not null,
    computed_at      timestamptz   not null default now()
);

-- The page lists a course's clusters biggest-first, and the staleness check reads computed_at.
create index idx_question_clusters_course on question_clusters (course_space_id, question_count desc);
