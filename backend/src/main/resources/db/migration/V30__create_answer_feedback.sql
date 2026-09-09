-- Phase 28.3 — was this answer any good, and what did it read.
--
-- **The citations are the point, not the verdict.** "Bad answer" is unactionable. "Bad answer, and
-- these six passages are what it read" separates the two failures that need opposite fixes:
-- retrieval brought the wrong passages, or retrieval was right and the generation was wrong.
-- Nothing in this project can currently tell those apart outside the 64-question fixture corpus.
--
-- It is also the only path from real use into the golden set. Recall@6, MRR and nDCG are measured
-- over questions written in August; no question a student actually asked has ever entered that set.
-- A thumbs-down is a candidate for it — the question, and the pages left blank for a person to
-- fill in — which is what keeps 11.1's instrument describing the product a year from now.
create table answer_feedback (
    id                uuid        primary key,
    course_space_id   uuid        not null references course_spaces (id) on delete cascade,

    -- The answer this verdict is about. Nullable and `on delete set null` rather than `not null`:
    -- question logging can be switched off (`studyloop.analytics.log-questions`), and a verdict
    -- given while it was off is still a verdict — it keeps its question text and its citations and
    -- loses only the join back to the event.
    question_event_id uuid        references question_events (id) on delete set null,

    submitted_by      uuid        not null references users (id) on delete cascade,

    -- Two-valued on purpose. A five-star scale invites a median and a median of opinions about
    -- answers is not a number anybody can act on; "this was wrong" is.
    helpful           boolean     not null,

    -- Optional, and the field the golden set actually wants: what the right answer would have been,
    -- or which lecture it should have found.
    reason            text,

    -- The citations exactly as they were on screen, in the shape the client was given them.
    --
    -- **Stored rather than re-derived, and that distinction is the whole feature.** Re-running
    -- retrieval for this question at report-reading time answers a different question — today's
    -- corpus, today's stages, today's thresholds — and would quietly explain away every failure it
    -- was collected to find. Same argument as V29, one table over.
    citations         jsonb       not null default '[]'::jsonb,

    -- Denormalised from the event: a verdict has to survive question logging being off, and the
    -- instructor's page has to be able to show what was asked without a join that may find nothing.
    question          text        not null,

    created_at        timestamptz not null default now()
);

-- The instructor's confusion page reads this by course, worst first, most recent first. `helpful`
-- leads the sort in the application rather than the index; what the index has to make cheap is
-- "this course's feedback, newest first", which is every read this table has.
create index idx_answer_feedback_course on answer_feedback (course_space_id, created_at desc);

-- One verdict per person per answer. A partial unique index would be the wrong shape here — there
-- is nothing partial about it — and a plain unique constraint over a nullable column is exactly
-- right: two verdicts on the same event by the same member collide, and two verdicts recorded while
-- logging was off (both null) do not, because null is not equal to null. The second click changes
-- the verdict rather than adding one, which the service does with an upsert.
create unique index uq_answer_feedback_event_member
    on answer_feedback (question_event_id, submitted_by)
    where question_event_id is not null;

comment on table answer_feedback is
    'Phase 28.3: a reader''s verdict on one answer, with the passages that answer was shown with. '
    'Zero provider calls and zero query-time cost — nothing reads this on the chat path.';
