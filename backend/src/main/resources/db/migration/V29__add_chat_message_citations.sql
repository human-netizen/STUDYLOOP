-- Phase 28.1 — the citations a stored answer was grounded on.
--
-- `ChatMessage`'s own comment has said since Phase 5 that citations are recomputed per answer and
-- deliberately not persisted, and that was right for the only reader history had: the model, which
-- replays a transcript and needs role and content and nothing else.
--
-- Showing that same history to a *person* changes what the row owes. An answer reads "the height
-- of a treap is O(log n) with high probability [2]", and a resumed thread without the source list
-- renders [2] as two characters of dead text — a citation that cannot be clicked in a product
-- whose central claim is that its answers can be. That is worse than an answer with no markers at
-- all, because a dead link reads as a broken one.
--
-- **Denormalised on purpose, and the precedent is three migrations back.** The chunks an answer
-- cited could in principle be recovered by re-running retrieval over the stored question, but that
-- is a different set: the corpus moves, documents are retired (27.3), and re-retrieval a week later
-- would attach *today's* passages to yesterday's sentence. `chat_cache_entries.citations` (V14)
-- stores exactly this, verbatim, for exactly this reason — what was shown is a fact about the past
-- and is stored as one.
--
-- Default '[]' rather than null so every reader gets a list: a USER turn has no citations and
-- neither does an answer from before this migration, and "no sources" is the honest rendering of
-- both.
alter table chat_messages
    add column citations jsonb not null default '[]'::jsonb;

comment on column chat_messages.citations is
    'Phase 28.1: the Citation list this turn was shown with, as it was shown. Empty for USER and '
    'GENERAL turns and for every message stored before the read path existed.';

-- The index the thread list needs, and the reason it did not exist before is that nothing ever
-- listed threads. V8 indexed `(course_space_id)` alone, which was the right index for the only
-- question anyone asked of this table — "does this conversation belong to this course?" — reached
-- by primary key anyway. The sidebar asks a different one on every open: *this member's* threads
-- in this course, newest first. `updated_at desc` is in the index so the ordering is read off it
-- rather than sorted after the fact.
create index idx_chat_conversations_owner
    on chat_conversations (course_space_id, created_by, updated_at desc);

