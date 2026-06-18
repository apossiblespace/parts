# ADR 0013: Body location as a structured point, not free text

## Status

Accepted — 2026-06-17.

## Context

A Part can record where in the client's body it is felt — its somatic
locus (e.g. "front of left shoulder"). The `body_location` column has
existed since the first schema as `TEXT` / `(s/nilable string?)`, wired
through the `part-update` change-event and the bitemporal record, but it
has never had a UI: `part-form` only edits type, label, and notes.

The pre-launch feature gives it one. The therapist pinpoints the location
on a body **silhouette** — a bespoke SVG with a front figure and a back
figure — by clicking; the sidebar shows a scaled-down figure with a dot,
and a modal offers a larger figure for precise placement. A "bonus" goal
is to translate the point into a human-readable, eventually localized
description.

Several product facts shape the representation:

- Body location is a **clinical field**. Like `notes`, it is excluded
  from **Render** (the client-facing PDF/thumbnail) and included in
  **Export** (the GDPR Art. 15/20 machine-readable copy). Whatever shape
  it takes is frozen into **immutable bitemporal history** — old-format
  rows live forever, so the format is expensive to change after launch.
- The stack already answers the serialization question. The wire is
  **Transit** end-to-end, so a keyword-keyed map round-trips FE↔BE
  natively. The only structured-data-at-rest pattern is **JSONB** (the
  `audit_log`'s `to_jsonb(NEW)` capture); there is no EDN-at-rest
  anywhere, and `jsonista` is already a backend dependency.
- The silhouette SVG carries **no region metadata** — it is raw geometry,
  laid out as two figures side by side. Any automatic labeling needs a
  region map we author ourselves.
- **i18n is on the roadmap.** A stored English label ("left shoulder")
  would be unlocalizable and would freeze language into the clinical
  record.

## Decision

`body_location` becomes a **single nilable `JSONB` value** holding a
structured point — one location, one view:

```clojure
{:view "front"   ; or "back" — explicit, never inferred
 :x    0.42      ; normalized 0..1 within that figure
 :y    0.31}
```

- **One JSONB value, not separate columns.** A location is a value object:
  `view`/`x`/`y` are meaningful only together and are all-set-or-all-null,
  so one nilable value gives clean "NULL = no location" without a
  hand-enforced cross-column invariant. There is no query-by-region need.
  It keeps the bitemporal table and the change-event `:data` map at one
  key, and — see below — lets the future region code be added with no
  migration.
- **JSONB, not EDN-in-TEXT.** JSONB is the project's established
  structured-at-rest pattern; it keeps the `audit_log`'s `to_jsonb`
  capture readable as nested JSON rather than an escaped opaque string;
  and Transit already carries the map on the wire, so the only
  serialization boundary is the DB.
- **Explicit `:view` + per-figure coordinates.** The stored point is
  independent of the asset's layout — it survives re-cropping, restyling,
  or relaying-out the silhouette, because it does not encode "front = the
  left half of one 600px image". The two figures are presented by cropping
  the single 600×600 asset to its left / right half (a CSS `background-image`
  at `background-size: 200% 100%`, positioned left for front, right for back)
  rather than by splitting it into two files: the silhouette draws both
  figures with compound paths that span the full width, so they cannot be cut
  apart, and cropping yields the same per-figure 0..1 coordinate space without
  touching the asset.
- **No new change-event type.** Editing the location is a Part `:update`
  (`body_location` is already in the `part-update` keys), riding the
  existing all-or-nothing batch — exactly like a canvas move is an
  `:update`, not a bespoke event.
- **Launch ships the point only.** The human-readable description is
  deferred: when built, a stable, language-neutral **region code** (e.g.
  `"shoulder-left"`) is added as *another key in the same JSONB value* —
  no migration. The code, never an English label, is what gets stored;
  the UI and Export localize it at display time. Storing the code (rather
  than re-deriving from the point) also freezes the semantic meaning at
  authoring time, so re-authoring the region map later cannot silently
  re-label a client's historical record.

## Alternatives considered

- **Keep free text.** Rejected: it can't redraw the dot, and "translate
  to a human-readable description" has nothing structured to translate.
  The free-text need is already served by `notes`.
- **Separate columns** (`body_view`, `body_x`, `body_y`, …). Rejected:
  forces a hand-enforced all-or-nothing-null invariant, fans the value
  out across the bitemporal machinery, and buys query-by-region we don't
  need. The future region code would need a new column instead of a free
  key.
- **EDN-in-TEXT.** Rejected: would be the only EDN-at-rest in the project
  and would degrade the `audit_log` to escaped strings.
- **Whole-canvas coordinates with `:view` inferred from x.** Rejected:
  welds every historical row to one specific 2-up asset and a magic
  midline constant.
- **Store the derived English label.** Rejected on i18n: unlocalizable,
  and freezes language into the clinical record.
- **Replace the silhouette with a region-labeled anatomical SVG.**
  Rejected: throws away the bespoke designed asset and imports a
  medical-diagram aesthetic at odds with the macOS-HIG craft bar.

## Consequences

- **Render** is unaffected — it renders structure only and never reads
  `body_location`.
- **Export** emits the value verbatim. Between this launch and the region
  feature, an export's `body_location` is **bare coordinates** — faithful
  and machine-readable (Art. 20), but not human-intelligible until the
  region code ships, at which point future exports gain meaning
  automatically. Accepted as in-spec for a machine-readable export
  (ADR-0010) at concierge-launch volume.
- The `JSONB` column needs a next.jdbc read/write handler, or it
  round-trips as an opaque `PGobject` instead of a Clojure map.
- The `::body_location` spec changes from `(s/nilable string?)` to a
  nilable map (`:view` enum, `:x`/`:y` in 0..1); the change-event
  `part-update` gate validates the new shape.
- The single-point shape is deliberate. Multi-region geometry ("across
  both shoulders", a location on both views) would extend the same JSONB
  value (a `:shape`, or `:points` vector) without breaking single-point
  rows.
