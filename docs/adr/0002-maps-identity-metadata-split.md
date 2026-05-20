# ADR 0002: Maps split into identity + bitemporal metadata

## Status

Accepted — 2026-05-12.

> Terminology: 'System' renamed to 'Map' (2026-05, TASK-015) — the decision below is unchanged, only the noun.

## Context

The `maps` table originally carried both identity (id, owner_id) and mutable metadata (title, viewport_settings) on the same row, non-temporally. The scrubber's promise of "replay the Map at time T" was therefore partially a lie — Parts and Relationships replayed, but Map metadata always showed its current value.

Two product pressures changed the calculus:
1. **Title history matters clinically.** Therapists rename Maps as their understanding refines ("Anxious thoughts" → "Work-related anxiety"). A scrubber that shows the rename-of-the-week while replaying an old session is wrong.
2. **The multi-user roadmap adds more metadata.** Sharing flags, `client_id`, viewing/editing permissions — all clinically meaningful and audit-relevant. "Who could view this Map at session T" is a real audit question, especially for mental-health data.

Once metadata needs to replay, we faced a structural fork:

- **Option A: make `maps` fully bitemporal.** Drop `deleted_at`; deletion is retraction; one table. *Cost:* `parts.map_id REFERENCES maps(id)` breaks (FK requires unique target). App-layer integrity only.
- **Option B: identity-only `maps` + bitemporal `map_metadata`.** Two tables. `parts.map_id → maps.id` stays a real FK. Bitemporal metadata is uniform with `parts`/`relationships`.

## Decision

**Option B.** `maps` is identity-only (id, owner_id, created_at, deleted_at, actor_id). `map_metadata` is a separate bitemporal table holding the changing attributes (title now; future sharing/permissions/client_id).

```sql
CREATE TABLE map_metadata (
  id          UUID NOT NULL,
  map_id      UUID NOT NULL REFERENCES maps(id),
  title       TEXT NOT NULL,
  valid_at    TSTZRANGE NOT NULL,
  sys_period  TSTZRANGE NOT NULL DEFAULT tstzrange(now(), 'infinity', '[)'),
  actor_id    UUID NOT NULL REFERENCES users(id),
  EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
);
```

`viewport_settings` is no longer a Map attribute at all — it's per-viewer UI state, moved to browser-local storage. If cross-device persistence becomes a requirement, add a `user_viewport_settings (user_id, map_id, settings)` table (non-temporal).

## Why this fit

- **Identity-vs.-state ontology.** A Map's identity (it exists, it's owned by someone) doesn't change. Its metadata does. The two-table shape mirrors the ontology — Hickey's "place vs. value": `maps` is the place, `map_metadata` rows are the values flowing through it.
- **FK enforcement preserved.** `parts.map_id` keeps its real FK to `maps.id`. Future `map_shares.map_id`, etc. also work.
- **Future-proofs metadata growth.** Adding sharing/permissions/client_id is "add columns to `map_metadata`," same as adding columns to `parts`. No special case.
- **Uniform write API.** `bitemporal/insert!` / `update!` / `correction!` / `retract!` work on `map_metadata` the same as `parts`. No new API.

## Consequences

- `entity/map/fetch` and `entity/map/index` JOIN with current metadata. One extra query per fetch (cheap, indexed via `map_metadata_current`).
- `entity/map/delete!` retracts metadata + children, then soft-deletes the maps row.
- `erasure/purge-account!` must `DELETE FROM map_metadata` before `DELETE FROM maps` to satisfy the FK.
- The pattern "metadata is bitemporal in a side table" is now established for *non-uniform-shape* entities. Parts and Relationships stay entity-snapshot because all their attributes share the same temporal granularity.
- Future "fully bitemporal Maps" alternative is *not* reopened by this ADR — Option A was considered and rejected for the FK-loss cost.
