# ADR 0002: Systems split into identity + bitemporal metadata

## Status

Accepted — 2026-05-12.

## Context

The `systems` table originally carried both identity (id, owner_id) and mutable metadata (title, viewport_settings) on the same row, non-temporally. The scrubber's promise of "replay the System at time T" was therefore partially a lie — Parts and Relationships replayed, but System metadata always showed its current value.

Two product pressures changed the calculus:
1. **Title history matters clinically.** Therapists rename Systems as their understanding refines ("Anxious thoughts" → "Work-related anxiety"). A scrubber that shows the rename-of-the-week while replaying an old session is wrong.
2. **The multi-user roadmap adds more metadata.** Sharing flags, `client_id`, viewing/editing permissions — all clinically meaningful and audit-relevant. "Who could view this System at session T" is a real audit question, especially for mental-health data.

Once metadata needs to replay, we faced a structural fork:

- **Option A: make `systems` fully bitemporal.** Drop `deleted_at`; deletion is retraction; one table. *Cost:* `parts.system_id REFERENCES systems(id)` breaks (FK requires unique target). App-layer integrity only.
- **Option B: identity-only `systems` + bitemporal `system_metadata`.** Two tables. `parts.system_id → systems.id` stays a real FK. Bitemporal metadata is uniform with `parts`/`relationships`.

## Decision

**Option B.** `systems` is identity-only (id, owner_id, created_at, deleted_at, actor_id). `system_metadata` is a separate bitemporal table holding the changing attributes (title now; future sharing/permissions/client_id).

```sql
CREATE TABLE system_metadata (
  id          UUID NOT NULL,
  system_id   UUID NOT NULL REFERENCES systems(id),
  title       TEXT NOT NULL,
  valid_at    TSTZRANGE NOT NULL,
  sys_period  TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id    UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
```

`viewport_settings` is no longer a System attribute at all — it's per-viewer UI state, moved to browser-local storage. If cross-device persistence becomes a requirement, add a `user_viewport_settings (user_id, system_id, settings)` table (non-temporal).

## Why this fit

- **Identity-vs.-state ontology.** A System's identity (it exists, it's owned by someone) doesn't change. Its metadata does. The two-table shape mirrors the ontology — Hickey's "place vs. value": `systems` is the place, `system_metadata` rows are the values flowing through it.
- **FK enforcement preserved.** `parts.system_id` keeps its real FK to `systems.id`. Future `system_shares.system_id`, etc. also work.
- **Future-proofs metadata growth.** Adding sharing/permissions/client_id is "add columns to `system_metadata`," same as adding columns to `parts`. No special case.
- **Uniform write API.** `bitemporal/insert!` / `update!` / `correction!` / `retract!` work on `system_metadata` the same as `parts`. No new API.

## Consequences

- `entity/system/fetch` and `entity/system/index` JOIN with current metadata. One extra query per fetch (cheap, indexed via `system_metadata_current`).
- `entity/system/delete!` retracts metadata + children, then soft-deletes the systems row.
- `erasure/purge-account!` must `DELETE FROM system_metadata` before `DELETE FROM systems` to satisfy the FK.
- The pattern "metadata is bitemporal in a side table" is now established for *non-uniform-shape* entities. Parts and Relationships stay entity-snapshot because all their attributes share the same temporal granularity.
- Future "fully bitemporal Systems" alternative is *not* reopened by this ADR — Option A was considered and rejected for the FK-loss cost.
