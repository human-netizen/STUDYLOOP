-- Spaced repetition (Phase 8.1). One review_states row per flashcard holds the SM-2 bookkeeping
-- that decides when the card comes back: the ease factor, the current interval, how many
-- consecutive successful reviews it has had, and the calendar day it is next due.
--
-- due_on is a date, not a timestamp: "due today" is a calendar question, and storing an instant
-- would make a card reviewed at 23:00 come back at 23:00 rather than tomorrow morning.
create table review_states (
    flashcard_id     uuid             primary key references flashcards (id) on delete cascade,
    ease_factor      double precision not null default 2.5,   -- SM-2 EF, floored at 1.3
    interval_days    int              not null default 0,     -- days between the last review and due_on
    repetitions      int              not null default 0,     -- consecutive grades >= 3; reset by a lapse
    lapses           int              not null default 0,     -- how many times this card has been failed
    due_on           date             not null,
    last_reviewed_at timestamptz
);

-- The review queue asks one question: which of my cards are due on or before today? Ordering by
-- due_on puts the most overdue first.
create index idx_review_states_due on review_states (due_on);

-- Missed quiz questions auto-enroll as flashcards (Phase 8.1). Recording which question a card
-- came from makes that idempotent: retaking the same quiz and missing the same question again
-- must not mint a second card. Partial unique index, so hand-made cards (null) are unconstrained.
alter table flashcards
    add column source_quiz_question_id uuid references quiz_questions (id) on delete set null;

create unique index uq_flashcards_owner_quiz_question
    on flashcards (created_by, source_quiz_question_id)
    where source_quiz_question_id is not null;

-- Backfill: every card that already exists gets a fresh state, due today, so the queue is
-- populated from day one rather than only for cards created after this migration.
insert into review_states (flashcard_id, due_on)
select id, current_date
from flashcards;
