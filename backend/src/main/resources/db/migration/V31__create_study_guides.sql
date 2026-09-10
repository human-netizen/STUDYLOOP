-- Phase 22 — a study guide written from the course's own materials, section by section.
--
-- **Two tables and not one, because a section is the unit of grounding.** ZenLearn passes one
-- `context` string into a whole generated document and has `validate_content` — its grounding
-- check — off by default. Retrieving once for the guide and writing every section from that one
-- context blurs the citations: a claim in section 5 carries a source number that was retrieved for
-- section 1, and there is no row anywhere that could say otherwise. Retrieval happens per section,
-- so the citations belong to the section, so the section is a row.
--
-- It is also what makes a *gap* expressible. A section whose retrieval the confidence gate refuses
-- is written down as covered = false with no body — "the course materials don't cover this" — and
-- that negative output is useful to a student, impossible for an ungrounded generator to produce,
-- and simply not representable in a single text column holding a finished document.
create table study_guides (
    id              uuid        primary key,
    course_space_id uuid        not null references course_spaces (id) on delete cascade,

    -- Scoped to the member who asked, exactly as a video is (Phase 21). A guide can be grounded on
    -- the asker's own OWNER-visibility notes, so the finished guide inherits their visibility. A
    -- course-wide guide would need a second grounding pass over course-visible material only, plus
    -- a promotion step with a guard on it, and that is a phase rather than a column.
    requested_by    uuid        not null references users (id) on delete cascade,

    topic           text        not null,

    -- QUEUED → PLANNING → WRITING → READY, with FAILED and REFUSED terminal. The same shape as
    -- documents.status and video_jobs.status, for the reason VideoJobStatus gives: a second
    -- polling protocol is a second thing to get subtly wrong, and what it gets wrong is always
    -- the terminal state.
    status          varchar(20) not null,

    -- The language of the *material*, decided by the planner from what it retrieved, not by the
    -- language of the topic anyone typed (Phase 19.3's rule, and Phase 21.2 applies it the same
    -- way). ENGLISH at insert is a default and not yet a finding.
    language        varchar(20) not null,

    -- Progress, and the only reason it is two columns rather than a percentage: a percentage
    -- cannot say "4 of 6" while it is happening and cannot say "1 of the 6 was a gap" afterwards.
    -- `sections_planned` is set once the outline call returns, so a guide that is still PLANNING
    -- honestly reports 0 rather than a number nobody has decided yet.
    sections_planned  integer   not null default 0,
    sections_written  integer   not null default 0,

    -- Model calls actually spent on this guide: one outline plus one per section it wrote. Stored
    -- rather than derived from ai_usage_events because that table is keyed by user and window, and
    -- the question this answers is about one guide. It is the number 22.4 is a claim about — "one
    -- planning call plus one call per section" is checkable against a row rather than asserted.
    model_calls     integer     not null default 0,

    error           text,
    created_at      timestamptz not null default now(),
    completed_at    timestamptz
);

-- The library reads this by course and requester, newest first, which is every read it has.
create index idx_study_guides_course_member
    on study_guides (course_space_id, requested_by, created_at desc);

create table study_guide_sections (
    id          uuid        primary key,
    guide_id    uuid        not null references study_guides (id) on delete cascade,

    -- 1-based, and the guide's reading order. Not an ordering by created_at: sections are written
    -- in order today, and a row whose position is implied by when it happened to be inserted is a
    -- row that reorders itself the day anything runs two of them at once.
    position    integer     not null,
    heading     text        not null,

    -- Null exactly when covered is false. The gap is the whole point of the pair: a section the
    -- corpus cannot support is *listed*, with no prose under it, rather than written from the
    -- model's own knowledge and rather than being silently dropped from the outline. A student
    -- learning that their materials do not cover step 3 has learned something true.
    body        text,
    covered     boolean     not null,

    -- Mermaid source for this section, or null (Phase 22.2). Text, because that is what Mermaid is
    -- — a deterministic diagram derived from the words — and it is generated in the same call as
    -- the body, so a diagram costs nothing beyond the tokens it occupies. **No image generation**:
    -- see PLAN.md's "deliberately not building" table, where a generated picture of a B-tree is
    -- decorative at best and actively misleading at worst.
    diagram     text,

    -- The sources this section was written from, in the shape the client is given them, numbered
    -- from one *within the section*. A snapshot rather than a foreign key, on V29's and V30's
    -- reasoning: the chunks may be gone (27.3) and the guide must still read, and re-deriving
    -- them later would attach today's corpus to a sentence written a week ago.
    citations   jsonb       not null default '[]'::jsonb,

    created_at  timestamptz not null default now()
);

-- One row per position per guide, which is what makes the read below deterministic.
create unique index uq_study_guide_sections_position
    on study_guide_sections (guide_id, position);

comment on table study_guides is
    'Phase 22: a grounded study guide. One planning call plus one call per section, charged to the '
    'Phase 10.2 budget through AiUsageRecorder like every other model call in the product.';

comment on column study_guide_sections.covered is
    'False means the confidence gate refused this section''s retrieval: it is listed as a gap and '
    'has no body. Grounding is mandatory here, not a flag — the inversion of ZenLearn''s default.';
