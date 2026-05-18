# ADR 0006: System access middleware; not-owned renders as 404

## Status

Accepted — 2026-05-15.

## Context

Four single-System handlers — `get-system`, `update-system`, `delete-system`, `process-changes` — each repeated the same beat: extract the JWT subject, fetch the System, check ownership, return 200 or 403. Three inlined the check; one used `user-can-modify-system?`. The access rule was copy-pasted, untested as a unit, and inconsistent.

Two pressures changed the calculus:

1. **The Client role is planned.** CONTEXT.md flags a future user role with restricted access to a Therapist's System. A one-line ownership predicate copy-pasted across handlers can't grow into "viewing vs. editing vs. none, per (user, System)" — access policy needs a place to live as it grows.

2. **403 vs. 404 leaks existence.** A non-owner receiving 403 (where a non-existent System gets 404) can enumerate which System IDs are valid by status code alone. For a mental-health app dealing with highly sensitive client data, this is the kind of probe-protection CONTEXT.md's "conservative posture" should already provide.

`system/fetch` also turns out to be the wrong tool for an auth check — it JOINs metadata and loads every part and relationship. Fine for the read endpoint, wasteful for `process-changes` (a hot path that runs on every batched edit) and `delete-system`, which only need to know who owns the row.

## Decision

**Introduce `wrap-system-access`** — a Reitit route middleware on the `/api/systems/:id` route group (covering `GET/PUT/DELETE`, `/changes`, `/pdf`). It fetches the `systems` identity row only, checks ownership against the JWT subject, and either calls the handler or throws `:not-found`. Handlers on those routes no longer self-check.

**Missing and not-owned both render as 404.** A non-owner cannot distinguish "the System doesn't exist" from "the System exists but isn't yours." The standard `:not-found` exception handler renders both as byte-identical 404 responses.

**Gate-only, no injection.** The middleware doesn't `assoc` the fetched System into the request. After pulling auth out, no surviving handler reads it — injecting unused state would be dead weight.

The ownership check leans on the **identity/metadata split** from ADR-0002 — a new `entity/system/fetch-identity` reads just the `systems` row, returning `nil` for missing or soft-deleted Systems. `fetch` is refactored to compose it; the heavy fetch lives once.

## Consequences

- The non-owner assertions relocated from `api/systems_test.clj` (handler level) to `middleware_test.clj` — where the rule now lives.
- `update-system` lost its `existing` fetch and a now-pointless `:owner_id` re-assoc (`system/update!` already `select-keys`'s to `:title`), shrinking from 14 lines to 6. Pulling auth out surfaced adjacent shallowness.
- **Wire-visible behavior change:** a non-owner request now returns 404, not 403. The frontend already routes the not-available case through one fetch-failure path; clients treat both as "System unavailable."
- The future Client role plugs in here: `wrap-system-access` extends to read an access level off the identity row (or a future `system_shares` table per ADR-0002) and gates per (user, System, operation) — without re-spreading checks across handlers.
