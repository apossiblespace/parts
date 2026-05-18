# ADR 0005: The change-event module — committed mutations only; presence is a separate channel

## Status

Accepted — 2026-05-14.

## Context

A **change-event** — the intent to mutate a System's contents — had no module. Its map shape (`{:entity :type :id :data}`) was reconstructed by hand at four sites: the frontend `:system/*` handlers built it, `queue.cljs`'s `normalize-event` multimethod re-shaped it (8 near-identical methods), `api/systems.clj`'s `process-changes` re-shaped it again, and `systems_events.clj`'s `process-change` multimethod re-shaped it a third time (defensive `(keyword …)`, `(assoc data :id :system_id)`). Normalization was literally split in half across the client/server seam: the frontend *stripped* `:id` / `:system_id`, the backend *re-attached* them. There was no interface to test against — the contract lived in four implementations kept in sync by hand.

Two scoping questions surfaced while designing the module:

1. **Should `:position` be a first-class change-event type?** It existed only as a key-renaming convenience — `{:x :y}` translated to `{:position_x :position_y}` at the very last step in `process-change`. No consumer branched on the `:position` tag; both `:position` and `:update` called `entity/part/update!`.

2. **Does multiplayer need `:position` first-class?** The instinct was yes — other users should see node movements. But "see other users' movements" decomposes into two axes with opposite requirements: *committed* position changes (durable, ordered, all-or-nothing — ADR-0003) and *ephemeral* live drag frames (lossy, last-write-wins, gone on disconnect). Only the first is a change-event.

## Decision

**Introduce `aps.parts.common.change-event`** — a `.cljc` module that owns the change-event vocabulary: named per-op constructors (producer fail-fast), a `parse` trust gate (consumer-side re-coerce + re-validate), and the canonical shape. It depends on `common.models.*` specs for `:data` validation but never constructs domain entities — defaulting and entity construction stay in the model layer. `queue.cljs`'s `normalize-event` multimethod and `process-change`'s reshaping both collapse into it.

**Change-events are committed mutations only.** The vocabulary covers exactly what goes through the all-or-nothing batch into the bitemporal record. `:position` is **not** a change-event type — it collapses into `:update`. A `part-moved` constructor survives as the producer's intent-name, but it builds an `:update` on the wire.

**Presence is a deliberately separate, not-yet-built channel.** Ephemeral multiplayer data — live drag frames, cursors, remote selections — is *not* a change-event. When multiplayer lands, presence gets its own channel (lossy, peer-broadcast, persistent socket), not a slot in the change-event vocabulary.

Canonical shape: `{:entity <kw> :type <kw> :id <id> :data <map>}`. `:entity` and `:type` are canonical keywords (the module owns coercion). `:system_id` is **off** the event — System scope is a property of the *batch*, injected by `apply-changes!` from the route.

## Why this fit

- **An empty slot in a vocabulary is an invitation.** A first-class `:position` type would look like the natural home for live drag frames — and presence would eventually squat there. Collapsing it forces presence to announce itself as a new concept.
- **The deletion test applied to a type:** `:position` carried no behaviour a consumer branched on. Deleting it concentrates nothing and removes a future foot-gun.
- **Direction of travel across the seam differs.** Change-events flow producer → applier (a write). Presence flows applier → other producers (a fan-out read). Conflating them is a category error.
- **Two front doors for two audiences.** A friendly constructor for the trusted producer; a strict `parse` for the untrusted consumer. The shared `.cljc` spec is what keeps them from drifting.

## Consequences

- `queue.cljs` loses its 8-method `normalize-event` multimethod. The `(when-not (:dragging event))` check there was already dead code — the live `:dragging` filter is in `system.cljs`, a ReactFlow-adapter concern outside this module's scope.
- `process-change` stops reshaping: no defensive `(keyword …)`, no `(assoc data :id :system_id)`. It receives canonical, already-validated events.
- `api/systems.clj`'s `process-changes` stops branching on `body-params` shape — `parse` handles single-vs-vector and validation.
- The change-event spec becomes the test surface — unit-testable on both sides, replacing today's HTTP-only coverage.
- When multiplayer is built, the presence channel is a new module alongside `change-event`, not a retrofit into it.
