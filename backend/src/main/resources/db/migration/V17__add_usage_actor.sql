-- Phase 10 — who spent it.
--
-- V14's ledger answers "what did this app cost", which is enough for a dashboard and not enough
-- for a budget: a per-user allowance needs a per-user total, and there was no column to group by.
-- Everything else about the row stays as it was — the ledger is still append-only, still priced
-- at write time, still one row per billable provider call.
--
-- Nullable on purpose. Not every call has a person behind it: a scheduled sweep or a backfill run
-- from a console is real spend with no actor, and forcing one would mean inventing a fake user or
-- dropping the row. A null here reads as "nobody's allowance", which is the truth.
--
-- on delete set null, not cascade: deleting a user must not erase what the provider was already
-- paid. The money left the account whether or not the account still exists.
alter table ai_usage_events
    add column user_id uuid references users (id) on delete set null;

-- The budget query is "this user, since this instant" — one range scan per gated request, so it
-- gets its own index rather than riding the occurred_at one the dashboard uses.
create index idx_ai_usage_user_recent on ai_usage_events (user_id, occurred_at desc);
