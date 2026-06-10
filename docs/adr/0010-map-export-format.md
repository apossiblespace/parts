# ADR 0010: Map export — full valid-time history as JSON

## Status

Accepted — 2026-06-10.

## Context

GDPR gives the data subject a right of **access** (Art. 15 — a copy of all
personal data held) and **portability** (Art. 20 — data they provided, in a
machine-readable form). Three now-published commitments bind us to a Map export
specifically (task-009):

- the **Terms of Service** promise a therapist *"can export your Maps at any
  time"* — a standing, self-serve capability;
- the **DPA** commits us to *assist the controller* with their clients' requests
  *"including by providing an export,"* and to *"delete or return"* client data
  on termination;
- the **Privacy Policy** offers the therapist portability of their *own account*
  data on request.

No export exists today. The shape is constrained by the model:

- The **client is the data subject** of the Map's clinical content; the
  **therapist is the controller** and Parts the **processor**. The client has no
  account — the therapist enters everything and owns the Map. So the export is
  the therapist's instrument, owner-scoped via the existing **Map access** rule
  (ADR-0006), serving both their own portability and their duty to a client.
- A **Map = one client's mapping** (CONTEXT.md); multi-Map management is
  post-launch.
- Map content is **bitemporal** (`parts`, `relationships`, `map_metadata`) —
  every change retained over a valid-time and a transaction-time axis. The
  existing readers (`as-of-now`, `as-of-valid`, `as-known-on`) are all
  *time-slice* and strip the temporal columns as internal; none returns the full
  history. Temporal SQL is quarantined to `db/bitemporal` by an
  architecture-fitness test.
- A sibling output already exists: server-side **Render**/PDF (ADR-0008), which
  deliberately *excludes* clinical fields because it is a client-facing hand-out.

## Decision

A Map export is **a machine-readable JSON copy of one Map's full valid-time
history**, downloaded self-serve by the Map's owner. Shape:

```json
{
  "format_version": "1",
  "exported_at": "2026-06-10T12:34:56Z",
  "map": { "id": "…", "created_at": "…",
           "title_history": [ { "title": "…", "valid_from": "…", "valid_to": null } ] },
  "parts": [ { "id": "…",
               "versions": [ { "type": "manager", "label": "…", "description": "…",
                               "position_x": 0, "position_y": 0, "width": 0, "height": 0,
                               "body_location": "…", "notes": "…",
                               "valid_from": "…", "valid_to": null } ] } ],
  "relationships": [ { "id": "…",
                       "versions": [ { "type": "protective", "source_id": "…", "target_id": "…",
                                       "notes": "…", "valid_from": "…", "valid_to": null } ] } ]
}
```

Supporting decisions:

- **Per-Map and self-serve; account-wide is not a day-1 feature.** The ToS
  promise that lands as a standing self-serve capability is *"export your Maps"* —
  which is per-Map (a Map = one client). The therapist's own *account-wide*
  Art-15 dump is a handful of fields offered on request (Privacy §9),
  hand-fulfilled at concierge scale, and a thin loop over the per-Map builder if
  ever surfaced.
- **Valid-time history at current belief — not the transaction-time cube.** The
  export folds across every `valid_at` version still believed (TT-current),
  exposing each version's valid interval. The transaction-time axis
  (`sys_period` — *when we recorded a correction*) is our own record-keeping,
  weakly in Art-15 scope and noise to a subject; it is omitted. This **includes
  retracted entities** — a Part the therapist removed is retained in history with
  a closed valid interval (only *erasure* deletes the rows) — which is correct
  for an "everything we hold" copy.
- **Clinical fields are included — the opposite of Render.** `notes` and
  `body_location` *are* the client's special-category data, so the export carries
  them. Render excludes them precisely because its purpose is a shareable
  structure-only hand-out; the export's purpose is a complete data-subject copy.
  Same fields, opposite calls, driven by purpose. What a controller withholds
  under Art-15 exemptions (third-party data, protected opinions) is the
  therapist's decision about what to *forward* — not the tool's.
- **Internal / controller columns are stripped.** `actor_id` (the editor —
  always the therapist today), `sys_period` (transaction time), and the `maps`
  identity's `owner_id` / `deleted_at` are controller/internal metadata, not the
  subject's data; re-import reassigns owner and actor and re-stamps transaction
  times. Only `valid_at` (as `valid_from` / `valid_to`, `infinity` → `null`), the
  entity `id`s, and the Map's `created_at` carry over.
- **Re-import-friendly shape, no importer.** Real entity `id`s, complete
  structure, and a top-level `format_version` make the payload a viable future
  input for the planned **Map-transfer** and **Scrubber-continuity** features.
  But a tested round-trip — replaying valid-time history back into the bitemporal
  tables — is its own post-launch task; no round-trip is promised now.
- **One new reader, quarantined.** The "all valid-time versions" read is added to
  `db/bitemporal` (the only namespace that may emit temporal SQL);
  `aps.parts.export` *composes* it with no temporal SQL of its own, preserving the
  architecture-fitness boundary. The endpoint mirrors `preview.svg` /
  `render.pdf`: `GET /api/maps/:id/export.json` under `wrap-map-access`,
  owner-scoped (missing and not-owned both render as 404).

## Alternatives considered

- **Full bitemporal cube (every valid × transaction row).** Rejected: it exposes
  our correction history — controller record-keeping, weakly in scope, noisy. The
  audit trail belongs with `audit_log`, hand-dumped if a regulator ever asks.
- **Exclude clinical fields, mirroring Render's structure-only output.** Rejected:
  it defeats the export's core Art-15 purpose — `notes` / `body_location` are the
  most important subject data to include.
- **Account-wide self-serve "export all my data."** Rejected for day-1: the
  standing ToS promise is per-Map; account-data portability is an on-request right
  over a few fields, hand-fulfillable for the founding circle.
- **PDF / human-readable export as the primary format.** Rejected: Art-20 wants
  machine-readable, and JSON serves both access and portability. A human-readable
  rendering, if ever wanted, is a presentation layer over the same JSON.
- **Honour the data-inventory's broad "include everything" marks**
  (`sys_period` / `actor_id` / `owner_id` ✅). Rejected: inconsistent with
  valid-time-only and a clean subject copy. The data-inventory is corrected to
  match, so AC #1's "key set matches the inventory exactly" holds against the real
  export.
- **Build the importer now (a round-trip guarantee).** Rejected: full-history
  re-import is hard and untestable without the consuming feature; a friendly
  *format* costs ~nothing, a guaranteed *round-trip* is speculative.

## Consequences

- One feature discharges three published duties at once: the ToS self-serve
  *"export your Maps,"* the DPA's DSAR-assist, and the DPA's return-on-termination.
- The export reveals **retracted Parts** (removed-but-retained) — consistent with
  the Privacy Policy's "full version history retained until erasure," but worth
  knowing: *removed from the canvas* ≠ *gone*.
- `db/bitemporal` gains a history reader; `aps.parts.export` is a new
  compose-only namespace; the architecture-fitness test stays green.
- The format is **versioned** (`format_version`), so the future importer /
  Map-transfer work has a stable, evolvable contract.
- The legal docs need **no change** — they are deliberately format-agnostic, and
  pinning "JSON" in them is avoided so the format can evolve.
- `audit_log` stays out (data-inventory §1, task-042 note); the "labelled
  access-only section" idea is deferred until the client role introduces a
  third-party actor.
