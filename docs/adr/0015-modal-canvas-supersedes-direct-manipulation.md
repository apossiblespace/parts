# ADR 0015: Modal canvas tools — supersedes ADR-0011

## Status

Accepted — 2026-07-04. **Supersedes ADR-0011** (direct-manipulation, no modes).

## Context

ADR-0011 removed the canvas's modes in favour of region-based gestures: drag a
Part's interior to move, drag its boundary ring to connect, drag empty canvas
to pan; the toolbar only chose what creation produced. The bet was that
physically distinguishable gestures wouldn't need a mode.

**Pre-launch usability testing falsified that bet.** Test users could not
discover how to draw a Relationship (the boundary ring affords nothing until
hovered) and could not figure out map manipulation generally. The specific
selection pain is **group selection**: selecting several Parts at once requires
holding Shift while dragging — an invisible modifier on an invisible gesture.

This is also the exact condition the `parts-canvas-pan-model` decision wrote
down as a re-validation trigger ("real evidence that users are confused";
"therapists manipulate clusters of Parts"). The trigger has fired.

## Decision

**Reintroduce explicit tools.** The canvas has a small tool palette; the active
tool determines what a drag means, so nothing depends on an undiscoverable
region or modifier:

- **Select (default):** click selects one; **drag on empty canvas draws a
  marquee** and selects the group inside (no Shift required — this is the fix
  for the reported pain); drag a Part moves the whole selection.
  Shift/⌘-click extends a selection.
- **Connect:** **drag from anywhere on a source Part to a target Part** —
  the whole body is the drag source while the tool is active, with a live
  preview line in the current type's colour. Drag-first because drawing *is*
  dragging in every reference tool (OmniGraffle, FigJam, draw.io): the
  first gesture a user tries must be the one that works. (A click-source-
  then-click-target variant was built first and dropped: without a
  rubber-band line the armed state was illegible, and the natural
  drag-from-body attempt did nothing — hands-on testing found it
  confusing.) A near-still press is guarded by a drag threshold so a
  plain click stays a click; self-loops are rejected outright by the
  model (`relationship/can-connect?`, which also blocks duplicate
  ordered pairs), and the connection preview enforces the same rule so
  it never snaps to a target the commit would drop. The new
  Relationship's type is the persistent Relationship-type selector — the
  "current ink" idea kept from ADR-0011. Connect is a **one-shot armed
  tool**, exactly like the Part-placement tools: it draws one Relationship
  then **auto-returns to Select**, with **Shift to stay armed** for drawing
  several, and Escape to disarm. Auto-return is deliberate mode-error
  insurance: Connect is the mode a user is most likely to get stranded in
  (trying to move a Part while it's active), so it self-exits by default.
  Consequence: Select and Hand are the only *persistent* modes; every
  creation tool (Part types, Connect) is one-shot and springs back to
  Select.
- **Hand:** pans the viewport.
- **Part placement** stays as ADR-0011's **one-shot armed tools** — arm a Part
  type, click to drop, the tool disarms. This protection is retained
  deliberately: the content is bitemporal, so a stray sticky click still mints
  permanent clinical history.
- **Resize (TASK-032) belongs to the Select tool.** Its aspect-locked corner
  handles (bounds and lock unchanged) appear only on a **single** selected Part
  in Select mode — not in Connect or Hand, and not for a multi-selection (a
  marquee selection is for move/delete; group-resize is deferred). The modal
  split removes the ring/corner collision ADR-0011 had to engineer: because
  Connect is now its own tool, the ring's connect role leaves Select mode, so a
  Part's whole body means "move" and its corners unambiguously mean "resize."

**Pan gets an explicit Hand tool because no invisible gesture is reliable
cross-platform.** Two-finger trackpad scroll is Mac-only; a mouse wheel pans
only one axis; middle-mouse-drag is missing on many laptops; Space-drag works
everywhere but is invisible — and invisibility is the disease being cured. The
Hand tool is the one pan affordance that is visible and identical on
Mac-trackpad, Mac-mouse, Windows, and Linux. Space-drag, two-finger scroll, and
middle-mouse-drag remain as **accelerators** for users who know them.

Consequently **drag-on-empty now means marquee, not pan** — reversing the
pan-on-drag-empty choice ADR-0011 preserved. That is intended: pan moved to its
own tool, freeing drag-empty for the group-selection users need.

## What is kept from ADR-0011

- **One-shot Part creation** (Shift to batch, Escape to disarm) — the
  bitemporal accidental-creation argument is unchanged.
- **The persistent Relationship-type selector as "ink"** — now scoped to the
  Connect tool rather than always-on.
- **Per-region cursor honesty and hover affordances** — still good, now
  reinforcing the active tool rather than substituting for one.

## Alternatives considered

- **Keep no-modes, add affordances only** (hover-highlight the ring, first-run
  hints). Rejected: testing showed the region model itself — not just its
  styling — is what users failed to form a mental model of. Better cues on an
  invisible gesture is a smaller version of the same mistake.
- **Marquee on Shift-drag, keep pan on drag-empty** (the status quo). Rejected:
  the reported pain *is* that Shift-drag is undiscoverable.
- **Rely on Space-drag / trackpad scroll for pan, no Hand tool.** Rejected on
  cross-platform grounds above.
- **Prototype the model before building.** Declined for launch: ship to staging
  and test there instead.

## Consequences

- ADR-0011 is superseded; its `.mode-*` removal is partly walked back, but its
  one-shot creation and ink-selector survive.
- The `parts-canvas-pan-model` memory is superseded — pan-on-drag-empty is gone.
- The empty canvas gains a **static empty-state** naming the first move ("start
  your first Session," then "add your first Part"); no interactive tour.
- The correctness of the model is unproven until staging testing; treat the
  tool set as revisable if testing pushes back.

## This is a hypothesis — how it gets validated

ADR-0011 was overturned by evidence; this ADR must not escape the same scrutiny.
It is a bet that explicit tools are discoverable — not a proven fix.

**Staging pass/fail criteria.** A first-time user, untold, within their first
sitting, can: (a) draw a Relationship, (b) select a group of Parts, (c) pan the
canvas, and (d) move a Part. Testers watch specifically for **mode errors**
(e.g. trying to move a Part while Connect is active). If those four are not
discoverable, the modal model has *not* passed — do not ship it by default.

**Revisit trigger.** If staging shows recurring mode errors or users who can't
discover tool-switching, reopen this decision. Candidate remedies, noted but
*not* pre-built (auto-return-after-connect is the one exception, already
adopted): spring-loaded / hold-to-connect temporary modes, a stronger active-tool
indicator, or making Select even harder to leave accidentally. Only the cheap
insurance is committed up front: a distinct per-tool cursor and a clearly-lit
active tool in the palette.

## Amendment — touch-primary devices (2026-07-27)

iPad testing (task-013) forced the tool set to diverge per pointer class.
On devices whose primary pointer is coarse (`pointer: coarse` — an iPad
finger; an iPad with a trackpad attached reports fine and keeps the
desktop model):

- **Drag-on-empty pans, in every tool.** The underlying canvas library
  cannot pair a touch marquee with pinch-zoom (its pan filter only
  consults the pan-button array for mouse events), and one-finger-drag
  pan is the universal tablet convention anyway.
- **Group selection is a long-press marquee** (hold ~500ms on empty
  canvas, then drag — the Freeform pattern), implemented outside the
  library: an armed-ring affordance, live selection preview, and a
  frozen viewport for the gesture's duration.
- **There are no persistent-tool buttons at all.** Hand exists to give
  *desktop* panning a visible, cross-platform affordance; on touch,
  drag-empty already pans in every tool, so a dedicated pan mode would
  only be a mode to get stranded in. And with Hand gone, Select is the
  only persistent mode — a button for a mode you can never leave is an
  indicator that controls nothing, and the armed creation buttons
  already indicate their own state. The palette is therefore just the
  stamps: the Part types and the Connect split. Armed tools disarm by
  tapping again or by completing the action; the H / Space-hold
  shortcuts are inert (a Smart-Keyboard iPad must not arm a tool that
  has no palette button to leave).

Consequently "Select and Hand are the only persistent modes" holds on
desktop only; on touch the sole persistent mode is Select, and it is
implicit — the resting state rather than a palette choice. The staging
pass/fail criteria apply per pointer class: on touch, (c) "pan the
canvas" is satisfied by the drag-empty convention, and (b) "select a
group" by the long-press marquee.

## Amendment — phones are view-only (2026-07-28)

The device-support stance (the decision task-013 asked for): **tablets
get the full editing canvas; phones view, never edit.** Rather than
half-support editing on a screen the toolbar was never designed for, a
phone gets a deliberate view-only Map — glanceable between sessions,
edited from a tablet or computer.

A phone is a coarse-pointer device whose smallest viewport side is
under 600px (`device/phone-primary?`), classified once at page load:
an iPhone in either orientation, never an iPad (768px shortest side),
never a narrow desktop window (fine pointer). Rotating cannot
reclassify — the smallest side doesn't change.

On a phone:

- The canvas is inert: drag pans, pinch zooms, nothing selects, drags,
  or connects (`toolbar/phone-interaction` — no tool applies). Unlike
  the Session-model's read-only canvas, selection goes too: the
  sidebar a selection would open is hidden, so a selection would light
  up and open nothing. Consequence, accepted: Part and Relationship
  notes are unreadable on phones.
- Chrome is the Map's name (static) and the back-to-list button —
  nothing else. A dismissible banner explains: "Parts is designed for
  use on a tablet or computer."
- The landing-page playground demo is view-only too — the phone rule
  outranks the demo-mode exemption in `sessions/read-only-reason`, so
  a first impression is a clean viewer, not a cramped editor.
- The Maps list swaps "Create a new Map" for the same guidance — a
  fresh account on a phone must not read as a dead end.

Enforcement is state-level, not just hidden chrome: `:phone` is a
`read-only-reason`, so the `require-editable` interceptor drops every
content mutation, and `require-not-phone` covers the lifecycle events
(rename, Session start/undo/trigger/activation, Map create) that are
legal without an active Session.
