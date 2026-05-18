# ADR 0004: `actor_id` row column is the audit-attribution contract

## Status

Accepted — 2026-05-12.

## Context

Every change to a temporal table fires the `audit_log_change` trigger, which writes an `audit_log` row attributing the change to an actor. There are two places the trigger can find the actor:

1. The `aps.actor_id` session-level Postgres variable (set via `SELECT set_config(...)`).
2. The `NEW.actor_id` column on the row being inserted / updated / deleted (a `NOT NULL UUID` referencing `users.id`).

The original implementation called a `set-actor!` helper at the top of every bitemporal write path — `update!`, `correction!`, `retract!`, and inside each entity wrapper's `create!`. The reading was that "callers must remember to set the session actor before writing."

Re-examining the trigger function revealed a three-level fallback:

```sql
actor := current_setting('aps.actor_id', true)::UUID;  -- 1: session var
IF actor IS NULL THEN
  actor := NEW.actor_id;                                -- 2: row column
END IF;
IF actor IS NULL THEN
  actor := tombstone_uuid;                              -- 3: hard fallback
END IF;
```

Since `actor_id` is `NOT NULL` on every temporal table, level 2 always succeeds when the session var is unset. For inserts / updates / retractions, the row's `actor_id` and the session var hold the same value (the user making the change). The session var is **redundant** in those cases.

The only place the session var has real semantic work to do: `purge-account!`. There the row's `OLD.actor_id` is the user being deleted — but we want audit rows to attribute to the *tombstone*, not the user. The session var overrides the row-column fallback for that single use case.

## Decision

**The `actor_id` `NOT NULL` row column is the audit contract.** Callers populate `actor_id` via `bitemporal/insert!`'s `:actor-id` opt; the trigger reads it via its fallback; audit attribution is correct. The session variable is reserved for *overriding* attribution — exactly one user (erasure) sets it.

`bitemporal/set-actor!` stays public but is documented as an erasure-only override:

> Override the audit trigger's actor attribution for the rest of this transaction. Only needed when the acting party differs from the row's `actor_id` column — e.g., during account erasure.

It is no longer called from `update!` / `correction!` / `retract!` or from any entity wrapper.

## Why this matters

- **Surface area shrinks.** The interface to the audit trigger is "set `actor_id` on the row" — enforced by the DB's `NOT NULL` constraint, not by docstring discipline.
- **Round-trips drop.** A logical update used to issue 3 redundant `SELECT set_config(...)` calls (one in `update!`, one in each nested `insert!`); now it issues zero.
- **`set-actor!`'s use site is searchable.** One caller (`purge-account!`); its purpose is obvious from where it lives.

## Consequences

- Existing audit-attribution tests (`test-audit-log-captures-actor`, the property tests) keep passing without modification — they were always covered by the row-column fallback.
- Future contributors who add a new write path don't need to know about `set-actor!`. They populate `actor_id` and the trigger does the right thing.
- If we ever want admin overrides (e.g., a "support tool" that attributes audit to a support actor instead of the row's actor_id), the `set-actor!` mechanism is already there — the pattern is established.
