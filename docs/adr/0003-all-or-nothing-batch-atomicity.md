# ADR 0003: All-or-nothing batch atomicity for the changes API

## Status

Accepted — 2026-05-12.

> Terminology: 'System' renamed to 'Map' (2026-05, TASK-015) — the decision below is unchanged, only the noun.

## Context

The frontend `queue.cljs` debounces React Flow events and POSTs them to `/api/maps/:id/changes` in batches — typically 1–5 events per batch (position changes from a drag, a rename, sometimes a dependent multi-step gesture like "create part + create relationship referencing it").

The original implementation wrapped each `process-change` method in a `try` / `catch` that returned `{:success false :error ...}` on any exception, while `apply-changes!` ran the batch inside a `with-transaction`. The behavior was **best-effort**: failures were reported per-change, successful changes within the same batch committed anyway, and the HTTP response was 207 Multi-Status when results were mixed.

For a clinical mental-health app with bitemporal storage, best-effort has a hidden cost: **every successful change becomes part of the immutable history.** A partially-applied batch leaves a permanent record of an incoherent state that the scrubber will faithfully replay forever. "The therapist saw what she saved" is a stronger and more defensible property than "the therapist saw mostly what she saved, plus a generated error toast."

We also considered hybrid models (per-batch opt-in via a header) and per-change best-effort. Both add API surface and decision-burden to clients.

## Decision

**Batches are all-or-nothing.** If any change in a batch throws, `apply-changes!`'s `with-transaction` rolls the whole batch back. The HTTP middleware translates the resulting `:batch-failure` `ex-info` into a 422 response with `{:error "...", :failing_change {…}}` body. The frontend handles 4xx as a single batch-level error pointing at the specific failing change.

The `try` / `catch` inside each `process-change` defmethod is removed. Per-change success reporting is kept for API stability (the `{:success true :result …}` envelope), but `:success false` never appears — failures throw instead.

The audit posture follows: **`audit_log` is committed-only.** Failed batches leave no audit row (the trigger fires inside the transaction; the rollback rolls audit entries back with everything else). Failed-attempt evidence — what was tried, by whom, when, and why it failed — goes to `mulog` instead. The two streams have clear, separate meanings:

- `audit_log` = "things that actually committed to the bitemporal record"
- `mulog` = application telemetry, including attempted-but-failed operations

## Consequences

- **Implementation:** entity functions threaded the surrounding transaction `tx` through `process-change`'s context map. Without that, the nested `bt/update!` would have run on a fresh connection and committed independently. A regression test (`test-batch-rollback-when-one-change-fails`) covers the invariant.
- **`bt/update!` / `correction!` / `retract!`** detect whether their argument is a Connection already in a transaction; if so, they don't open a nested `with-transaction` (which would prematurely commit). This is now baked into the `with-tx` private helper in `bitemporal.clj`.
- **Frontend:** the 207 branch is gone. The 4xx branch shows a clear error toast and re-fetches the Map to reconcile local state.
- **What this is not:** atomicity here is *batch-level*. Concurrent edits from a second client are not blocked; last-write-wins per (entity, attribute) in transaction-time is unchanged.
