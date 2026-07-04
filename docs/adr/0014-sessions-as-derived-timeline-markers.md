# ADR 0014: Sessions as derived timeline markers, not stamped foreign keys

## Status

Accepted — 2026-07-04.

Reverses the "not modeled yet" stance recorded for **Session** in `CONTEXT.md`
(previously: *"sessions are implied by clusters of changes… may become
explicit later"*). Pre-launch feedback made the concept explicit sooner than
planned.

## Context

Therapists asked to (a) create a therapy **Session** from the UI, (b) navigate
between Sessions to see the Map as it stood in each, and (c) get a sense of how
recently a given Part or Relationship entered the Map ("first appeared in
Session N"), plus attach a **Trigger** — what the session surfaced.

The obvious implementation is to stamp a `session_id` foreign key onto every
Part and Relationship at creation. Two facts argue against it:

- Map content is already **bitemporal** (ADR-0001): every record carries a
  `valid_at`, and history is immutable. "When did this Part first appear" is
  therefore *already recorded* — a second `session_id` column would duplicate,
  and could disagree with, the timeline.
- The concept is young and likely to change (the Trigger's shape alone moved
  twice during design). A model that stamps identity across two of the busiest
  tables is expensive to walk back.

## Decision

A **Session** is a thin entity: `sessions(id, map_id, ordinal, trigger,
anchor_valid_at)`. Its only load-bearing datum is the **anchor** — the
`valid_at` captured when the therapist opens the Session. Sessions are ordered
by anchor.

**Membership is derived, never stamped.** "Which records first appeared in
Session N" is the query *"earliest `valid_at` falls in
`[anchor_N, anchor_N+1)`"*. No `session_id` column is added to `parts` or
`relationships`.

Invariants that fall out of this:

- There is always exactly **one active Session — the latest.** Every edit lands
  in it. Starting a new Session implicitly closes the previous; there is no
  explicit "end."
- Editing requires an active Session — the first action on a new Map is "start
  your first Session," which doubles as the empty-canvas onboarding step.
- Navigating to a past Session is **read-only time-travel** — an `as-of-valid`
  read (the same seam the planned Scrubber will use). The canvas visibly shifts
  into a viewing-the-past state; edits are disabled until the therapist returns
  to the present.
- **PDF export follows the viewed Session** (Render at that Session's
  `valid_at`); the Maps-list **thumbnail always renders the tip**.

**Session mutation is deliberately narrow**, because a Session's anchor is a
structural boundary of the derived timeline:

- The `sessions` row is **non-temporal** — a Session's identity and anchor don't
  change, and we keep no history of trigger wording.
- The **trigger text is editable, for the active (latest) Session only.** A past
  Session's trigger is locked along with its content, consistent with
  "past is read-only." Editing text moves no anchor, so it shifts no membership.
- **Deletion is allowed only for the latest Session when it has no content and
  no activation** — the "started a Session by mistake" undo. It re-activates the
  previous Session and shifts nothing, because its range was empty. Deleting a
  non-latest or non-empty Session is *not* offered: it would silently re-home
  content into the previous Session and shift every downstream badge, and it cuts
  against the bitemporal immutability of the record. No "merge into previous"
  semantics are designed.

The **Trigger** launches as an annotation on the Session — a `trigger` text plus
an optional link (a `session_activations` link row, not a scalar column, so
growing from one activated Part to several later needs no table change). It is
*not* yet the canvas node it may become; see the Trigger term in `CONTEXT.md`.

Session *navigation* launches as a **discrete Session picker**, not the
continuous Scrubber — the picker is the first concrete slice of the Scrubber
concept and covers the asked-for "view the Map in Session N" without the
continuous-timeline build.

### The derivation is total — the invariant that makes it safe

Deriving membership from `valid_at` ranges is only sound if two things hold.
Both are guaranteed rather than hoped for:

1. **`valid_at` is monotonic with write order under every user-reachable path.**
   `insert!` defaults `valid_at` to `[now, ∞)` (wall-clock), and `write-ts`
   *clamps* each per-operation instant so clock-skew (NTP stepping back) still
   can't produce a `valid_at` at or behind an entity's existing bounds
   (`aps.parts.db.bitemporal`). The only path that writes *past* valid-times is
   `correction!`, which is an ops-level tool and **not reachable from the
   change-event model** (`:create/:update/:delete`). So "first appeared =
   earliest `valid_at` for an id" is stable: later edits and clock-steps never
   move it. If corrections are ever exposed to users, this invariant — and the
   recency badge — must be revisited.

2. **Every Part/Relationship has a Session whose range contains its earliest
   `valid_at`.** Guaranteed from both ends:
   - *New Maps:* the "editing requires an active Session" rule means a Session
     is created in an earlier action — so its anchor precedes any content. This
     also satisfies invariant (1)'s ordering guard: never emit content
     change-events in the same batch as the Session-create; if unavoidable,
     order the Session row first.
   - *Pre-existing Maps* (dev/staging; production is zero-data): a **backfill
     migration** synthesises a "Session 1" (empty trigger) anchored at or just
     before the Map's earliest content `valid_at`, for every Map that has any
     Parts/Relationships. Truly-empty Maps get their first Session on first
     edit.

   The payoff: readers never face a "belongs to no Session" case — the
   derivation is a total function, with no null-session branch to carry forever.

### Writes, audit, and data lifecycle

Sessions are a new entity carrying **clinical** data (the trigger text). They
must obey the same data-lifecycle rules as the rest of the Map, and because they
sit *outside* the bitemporal/change-event machinery, none of it is automatic:

- **Writes are out-of-band REST endpoints**, not change-events. Each Session
  operation is a single-row write to a non-temporal table; the change-event
  batch (ADR-0005) exists for multi-entity atomicity over *temporal* tables and
  buys Sessions nothing. Keeping them separate also enforces the anchor-ordering
  guard for free (a Session commits before the content it contains). ADR-0005 is
  not extended.
- **Audit is explicit.** The `audit_log` trigger fires only on temporal tables,
  so Session create / edit-trigger / delete / set-activation must each write an
  `audit_log` row themselves. Consequence: the operator "active user" metric now
  includes Session activity (see `CONTEXT.md`).
- **Erasure must delete Sessions.** The right-to-be-forgotten purge
  (`aps.parts.db.erasure`) must remove a purged user's `sessions` and
  `session_activations`, or clinical trigger text survives erasure. Note the
  architecture-fitness test greps `DELETE FROM` on *temporal* tables only — a
  non-temporal `sessions` table sidesteps that guard, so this must be verified
  deliberately.
- **Export must include Sessions.** The GDPR export (Art. 15/20) must carry the
  user's Sessions, triggers, and activations, or the export is incomplete.
- **The trigger is excluded from the client-facing PDF.** Render is
  structure-only and already excludes `notes` and `body_location` because the
  PDF is a client hand-out. The trigger and its activation marker are at least as
  sensitive, so they are **screen-only**: rendered in the app, excluded from
  Render/Export-to-PDF.

## Alternatives considered

- **Stamp `session_id` on every Part and Relationship.** Rejected: duplicates
  the bitemporal timeline, risks disagreeing with it, and is costly to remove
  when the concept changes. Deriving reuses machinery we already maintain.
- **Model "activated Part" as a scalar `activated_part_id` on the Session.**
  Rejected in favour of a link row: the feedback wants one activation at launch
  but explicitly must not foreclose several, and a link row also matches the
  future Trigger-node's one-description-to-many-Parts shape.
- **Ship the full continuous Scrubber now.** Deferred: the feedback needs
  discrete waypoints, which the picker delivers for a fraction of the build.

## Consequences

- "First appeared in Session N" is a **derived recency badge**, computed on
  read — cheap to compute, cheap to delete if the concept is reworked.
- The active-Session invariant means the Map is never editable "outside" a
  Session; UI must always present one.
- Because membership is a `valid_at`-range query, its soundness rests on the
  monotonicity + totality invariant above. The one path that could break it —
  user-facing `correction!` writing past valid-times — does not exist today;
  adding it is a decision that reopens this ADR.
