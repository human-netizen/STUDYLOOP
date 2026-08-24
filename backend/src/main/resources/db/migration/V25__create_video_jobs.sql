-- Phase 21.1 — the smallest honest job queue.
--
-- Every other slow thing in this application is an upload, and an upload gets away with `@Async`
-- and a status column because it finishes in seconds and nobody watches it. A video is minutes of
-- Manim and ffmpeg on somebody's laptop, so the three questions this table exists to answer are
-- the ones a status column alone has never answered here: what is it doing right now, what did it
-- cost in quality, and what happened to it when the process died halfway through.
--
-- **One writer.** The renderer is a Python sidecar and it never opens a connection to this
-- database — it is handed a scene plan and returns files. Spring owns every row below. Two
-- writers to one status column across two languages and two pools is precisely how a status
-- column starts lying, and Phase 20 spent four phases paying for one that did.

create table video_jobs (
    id                uuid        primary key,
    course_space_id   uuid        not null references course_spaces (id) on delete cascade,
    -- Who asked. Not merely provenance: 21.2 grounds the script on what *this* member may read,
    -- which can include their own OWNER-visibility notes, so the finished mp4 inherits their
    -- visibility rather than the course's and this column is the authorization check.
    requested_by      uuid        not null references users (id) on delete cascade,
    topic             text        not null,
    -- Which language the narration was spoken in — Phase 19.1's `documents.language` doing a
    -- second job. Recorded on the job rather than re-derived at playback, because the voice that
    -- actually read the script is a fact about the artifact, not about today's corpus.
    language          varchar(20) not null default 'ENGLISH',

    -- QUEUED / PLANNING / RENDERING / COMPOSING / READY / FAILED / REFUSED. Deliberately the same
    -- shape as document_status: the frontend already knows how to poll one of these, and a second
    -- polling idiom would be a second thing to get wrong.
    status            varchar(20) not null default 'QUEUED',
    -- The human sentence under the progress bar — "rendering scene 3 of 6". Free text and
    -- deliberately not an enum: it is a message, and the moment it becomes an enum somebody has
    -- to migrate the table to say something new to a waiting student.
    stage             text,

    -- 21.4's accounting, and the direct answer to AddNewFeature.md §4's third objection: the
    -- expensive half of this pipeline fails to static slides, and the whole complaint was that it
    -- does so *silently*. Three counters make the degradation a number the job page states and
    -- the phase report can average.
    scenes_total      int         not null default 0,
    scenes_animated   int         not null default 0,
    scenes_fallback   int         not null default 0,

    -- Relative to the videos root (the VIDEOS_DIR setting), mirroring documents.storage_path, so 23.4 inherits one volume
    -- decision instead of two. Null until COMPOSING succeeds.
    output_path       text,
    captions_path     text,
    duration_seconds  double precision,
    -- Why it stopped, for FAILED and for REFUSED alike. A refusal is not an error, but both are
    -- terminal states a student has to be given a reason for, and one column keeps the UI honest
    -- about that.
    error             text,

    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    -- When the worker was first contacted and when the file appeared. The difference is the phase
    -- report's headline number — wall clock per finished minute of video — and it cannot be
    -- reconstructed from created_at, because a job can sit QUEUED behind another render for
    -- longer than it takes to make.
    started_at        timestamptz,
    finished_at       timestamptz
);

-- The two reads this table gets: a member's own jobs in a course, newest first, and the daily cap
-- count. Both are (requester, course, time), so one index serves them.
create index idx_video_jobs_requester
    on video_jobs (requested_by, course_space_id, created_at desc);

-- The startup sweep's read: everything the last process left mid-flight. Partial, because the
-- interesting rows are a handful and the finished ones are the table.
create index idx_video_jobs_unfinished
    on video_jobs (status)
    where status in ('QUEUED', 'PLANNING', 'RENDERING', 'COMPOSING');

-- One scene, and why it looks the way it does.
--
-- These rows are the phase's evidence. `rendered_as` is the fallback accounting at the resolution
-- a person can act on — not "2 of 7 fell back" but *which* two and what the compiler said — and
-- `code_path` keeps the generated module beside the job, because a render that failed is
-- worthless as a bug report without the code that failed. A few kilobytes of text per scene.
create table video_scenes (
    id               uuid        primary key,
    job_id           uuid        not null references video_jobs (id) on delete cascade,
    scene_index      int         not null,
    title            text        not null,
    narration        text        not null,
    -- ANIMATED or SLIDE. What the scene ended up as, never what was planned for it — the plan is
    -- an intention and this column is a measurement.
    rendered_as      varchar(20) not null,
    -- Set only when an animated scene fell back: the sandbox layer that stopped it (REJECTED,
    -- KILLED, COMPILE, RENDER, TIMEOUT) plus the model's last stderr, truncated.
    fallback_reason  text,
    -- How many model calls this one scene took: 1, or 2–3 if the fix loop ran. Summed, this is
    -- the difference between the 14-call worst case and what a video actually costs.
    model_calls      int         not null default 0,
    code_path        text,
    duration_seconds double precision,
    created_at       timestamptz not null default now(),

    constraint uq_video_scene_index unique (job_id, scene_index)
);

create index idx_video_scenes_job on video_scenes (job_id, scene_index);

-- Which chunk each scene was written from — 21.2's segment→chunk link, and the reason a scene can
-- carry a citation at all. Deliberately its own table rather than a uuid[] on video_scenes: the
-- foreign key is what makes a citation pointing at a deleted document impossible to render, and
-- an array column would have made it merely unlikely.
create table video_scene_citations (
    scene_id   uuid not null references video_scenes (id) on delete cascade,
    chunk_id   uuid not null references document_chunks (id) on delete cascade,
    -- The [n] marker, 1-based, in the order the scene lists them.
    ordinal    int  not null,

    primary key (scene_id, chunk_id)
);
