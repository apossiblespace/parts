# ADR 0011: Direct-manipulation canvas — no modes, gestures by region

## Status

Accepted — 2026-06-10.

## Context

The map canvas was modal: a Move mode (the only state where nodes were
draggable), a Connect mode (the only state where the whole-node connect
overlay accepted pointer events), and four armed per-Part-type creation
modes. The toolbar selected *which mode the canvas was in*, and the two
core gestures — moving a Part and connecting two Parts — required a
toolbar round-trip to switch between, despite being physically
distinguishable. Edges always landed as `unknown`; setting their real
type meant selecting the edge and finding the sidebar form. The TASK-033
audit (see the task notes) catalogues the friction.

Two product facts shape the answer:

- Map content is **bitemporal** — an accidentally created Part is not
  free to undo; create-then-delete writes permanent history rows. The
  interaction model should make accidental creation hard.
- **Resize is coming** (TASK-032), and it competes for the same node
  boundary that connection-dragging wants.

Dragging on empty canvas already pans the viewport — a deliberate,
previously settled choice this rethink preserves.

## Decision

**The canvas has no modes.** The three drag gestures are disambiguated
by *where they start*, not by a toolbar state:

- drag a Part's **interior** → move the Part;
- drag a Part's **boundary ring** → start a Relationship (drop anywhere
  on the target node — the ring constrains drag *starts* only);
- drag the **empty pane** → pan the viewport.

The boundary is partitioned: the ring owns the **edges**, the **corners**
are reserved for the resize handles (TASK-032). The ring's thickness is
proportional to the Part's size and clamped — `clamp(8px, ~12%, 24px)`
per side — so resized Parts keep a usable hit target at both extremes.
Hovering a node tints the ring and shows a crosshair; the interior keeps
the grab cursor (per-region cursor honesty).

**The toolbar's only concern is what creation produces.** Its two groups
are different kinds of control, deliberately styled differently:

- **Part-type buttons are one-shot armed modes.** Arm, click the canvas,
  the Part is created and the tool disarms. Shift-click placement keeps
  the tool armed for batch adds; Escape disarms. One-shot wins because
  the failure modes are asymmetric: a stray click under sticky arming
  mints unwanted clinical data into the bitemporal record; under
  one-shot it costs a toolbar click.
- **The Relationship-type selector is persistent and always on** — the
  "current ink": every drawn edge gets the selected type. It defaults to
  `unknown` each session (a user who ignores it gets exactly the old
  behaviour) and is sticky within the session, not persisted across
  sessions. Rendered as one compact split control: a Spline icon (the
  old Connect-mode glyph, recast as "what connections are made of"), a
  colour dot showing the current type, and a caret opening a drop-up
  menu pairing every type's dot with its name, the current one
  checkmarked. The whole control triggers the menu; tooltip and
  aria-label carry the current type's name.

**Resize (TASK-032) is aspect-locked, corner handles only**, shown on
selection, bounded 60×60–400×400 (enforced in the resizer *and* at the
spec gate). The per-type Part shapes are organic SVGs stretched to the
node box; free resize would distort them, and a uniform scale-up still
buys label room. Width and height stay equal in practice but both
columns keep being written.

The keyboard surface shrinks to match: V/C (mode switches) are removed;
Escape means disarm + deselect. No per-type arming keys.

## Alternatives considered

- **Per-Relationship-type armed modes, mirroring the part tools** (the
  task's original sketch). Rejected: once drag-from-ring always
  connects, there is no disarmed state for a connect mode to toggle —
  the type choice is a persistent setting, not a mode. A single radio
  group across all ten buttons would have forced drawn edges back to a
  silent `unknown` fallback whenever a part tool was armed.
- **Sticky part-tool arming** (the previous behaviour). Rejected for the
  failure-mode asymmetry above; shift-click covers the batch case.
- **Fixed-pixel or unclamped-percentage ring.** Rejected: pixels ignore
  resize; pure percentage makes big Parts mostly ring and small Parts
  fiddly. Clamped percentage serves both.
- **Free resize (independent width/height), Shift to lock aspect.**
  Rejected: distorts the hand-drawn shape language; the long-label
  problem is served well enough by uniform scaling.
- **A row of six colour-swatch buttons for the relationship type** (the
  first implementation). Rejected on sight: six bare colour chips are
  illegible until each is hovered, so the row effectively hides the
  types behind six hovers — worse than the one click it was meant to
  avoid — while adding permanent toolbar noise. The split control's
  menu shows every colour *with its name* at the moment of choice,
  which teaches the palette and serves colour-blind users better. The
  trade: switching types costs two clicks instead of one. Acceptable
  because the selection is sticky (cost is per batch, not per edge);
  revisit via hotkeys or a swatch row if feedback shows heavy
  mid-session type switching.
- **Per-type keybindings (1–4 arm part types, etc.).** Deferred: ten
  bindings to serve a rare workflow, for users who are not
  keyboard-first. Additive later if demand shows.

## Consequences

- The armed part tool and the always-on type selector are both "lit" at
  once — unproblematic because they are visibly different kinds of
  widget (a button group vs. a dropdown control). Don't homogenise
  them into one row of look-alike buttons.
- The **node boundary is spoken for**: edges→connect, corners→resize.
  Adding edge-midpoint resize handles (ReactFlow supports them) would
  collide with the connect ring — that needs a redesign, not a prop.
- The connection drag preview can finally be honest: it renders in the
  selected type's colour rather than a generic line.
- Colour is the only at-a-glance type differentiator on canvas edges
  (the selector's menu carries names); per-type line styles are filed
  as TASK-033.01.
- Hover affordances (ring tint, tooltips) don't exist on touch;
  desktop-first is deliberate, touch discoverability is TASK-033.02.
- `nodesDraggable` is always true and the `.mode-*` CSS vocabulary
  disappears; the `:ui/tool-mode` state narrows to part-tool arming.
