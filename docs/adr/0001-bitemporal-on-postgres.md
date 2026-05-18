# ADR 0001: Bitemporal storage on PostgreSQL with EXCLUDE constraints

## Status

Accepted — 2026-05-12.

## Context

The scrubber feature requires that we replay a System's state at any past moment. Therapists also routinely *correct* past records ("last session that was a manager, not a firefighter") — so we need to distinguish "when a fact was true in the world" (valid time) from "when we recorded it" (transaction time). This is the textbook **bitemporal** problem.

We evaluated XTDB v2 early on (native bitemporal database) but eliminated it because its v2 storage architecture doesn't support PostgreSQL as a backend. The codebase already depends on PostgreSQL; running two databases for one product is operationally too expensive.

PG16 (our current version) lacks native temporal-column syntax (`WITHOUT OVERLAPS`, `PERIOD valid_at` FKs — those land in PG18). The lowest-friction native primitive is `EXCLUDE USING gist` with `tstzrange` columns.

## Decision

**Implement bitemporal storage in PostgreSQL using the entity-snapshot model**: each temporal table carries `valid_at tstzrange` (when the fact was true) + `sys_period tstzrange` (when we recorded it) + `actor_id` (who recorded it). The bitemporal invariant is enforced at the schema level:

```sql
EXCLUDE USING gist (id WITH =, valid_at WITH &&, sys_period WITH &&)
```

All temporal SQL is confined to a single namespace (`aps.parts.db.bitemporal`); other code calls its API and stays unaware of `valid_at` / `sys_period` / `tstzrange`. The architecture-fitness test enforces this confinement.

We pay one trade-off explicitly: **`relationships.source_id` / `relationships.target_id` reference `parts.id`, which is no longer unique** (multiple history rows share an id). The DB FK is dropped; integrity is application-layer (the bitemporal API checks that the referenced row exists before insert). This is acceptable for graph-edge references where partial failures degrade gracefully.

## Consequences

- The scrubber, audit trail, and right-to-erasure flow all build on this foundation.
- Migration to PG18+ becomes a pure schema change (swap `EXCLUDE USING gist` for `WITHOUT OVERLAPS`; add `PERIOD valid_at` FKs). No application code changes.
- The "no FK on bitemporal-id" pattern is established once. ADR-0002 chose to *avoid* extending it to `parts.system_id` by splitting systems instead.
- New temporal tables follow the same shape: bitemporal columns + EXCLUDE constraint + audit trigger.
