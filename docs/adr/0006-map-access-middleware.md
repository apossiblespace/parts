# ADR 0006: Map access middleware; not-owned renders as 404

## Status

Accepted — 2026-05-15.

> Terminology: 'System' renamed to 'Map' (2026-05, TASK-015) — the decision below is unchanged, only the noun.

## Context

Four single-Map handlers — `get-map`, `update-map`, `delete-map`, `process-changes` — each repeated the same beat: extract the JWT subject, fetch the Map, check ownership, return 200 or 403. Three inlined the check; one used `user-can-modify-map?`. The access rule was copy-pasted, untested as a unit, and inconsistent.

Two pressures changed the calculus:

1. **The Client role is planned.** CONTEXT.md flags a future user role with restricted access to a Therapist's Map. A one-line ownership predicate copy-pasted across handlers can't grow into "viewing vs. editing vs. none, per (user, Map)" — access policy needs a place to live as it grows.

2. **403 vs. 404 leaks existence.** A non-owner receiving 403 (where a non-existent Map gets 404) can enumerate which Map IDs are valid by status code alone. For a mental-health app dealing with highly sensitive client data, this is the kind of probe-protection CONTEXT.md's "conservative posture" should already provide.

`parts-map/fetch` also turns out to be the wrong tool for an auth check — it JOINs metadata and loads every part and relationship. Fine for the read endpoint, wasteful for `process-changes` (a hot path that runs on every batched edit) and `delete-map`, which only need to know who owns the row.

## Decision

**Introduce `wrap-map-access`** — a Reitit route middleware on the `/api/maps/:id` route group (covering `GET/PUT/DELETE`, `/changes`, `/pdf`). It fetches the `maps` identity row only, checks ownership against the JWT subject, and either calls the handler or throws `:not-found`. Handlers on those routes no longer self-check.

**Missing and not-owned both render as 404.** A non-owner cannot distinguish "the Map doesn't exist" from "the Map exists but isn't yours." The standard `:not-found` exception handler renders both as byte-identical 404 responses.

**Gate-only, no injection.** The middleware doesn't `assoc` the fetched Map into the request. After pulling auth out, no surviving handler reads it — injecting unused state would be dead weight.

The ownership check leans on the **identity/metadata split** from ADR-0002 — a new `entity/map/fetch-identity` reads just the `maps` row, returning `nil` for missing or soft-deleted Maps. `fetch` is refactored to compose it; the heavy fetch lives once.

## Consequences

- The non-owner assertions relocated from `api/maps_test.clj` (handler level) to where the rule now lives — `test/aps/parts/auth/middleware_test.clj`.
- `update-map` lost its `existing` fetch and a now-pointless `:owner_id` re-assoc (`parts-map/update!` already `select-keys`'s to `:title`), shrinking from 14 lines to 6. Pulling auth out surfaced adjacent shallowness.
- **Wire-visible behavior change:** a non-owner request now returns 404, not 403. The frontend already routes the not-available case through one fetch-failure path; clients treat both as "Map unavailable."
- The future Client role plugs in here: `wrap-map-access` extends to read an access level off the identity row (or a future `map_shares` table per ADR-0002) and gates per (user, Map, operation) — without re-spreading checks across handlers.
