# ADR 0017: Floating windows — non-modal, map-scoped editors

## Status

Accepted — 2026-09-13.

Amends the **saving model** in `CONTEXT.md`: the "modals are the exception"
clause now covers only true modals.

## Context

Three surfaces need more room than a sidebar form gives, but must not stop
the therapist from working on the canvas while they are open:

- **Body location** (ADR-0013): the two-figure silhouette. Today a modal.
- **Session trigger** (ADR-0014): a multi-line text. Today a modal with
  Save / Cancel.
- **Self statements** (planned, own ADR): a per-Part log of what Self said to
  the Part and what the Part said back, grouped by Session. A therapist reads
  this log *while* selecting Parts on the canvas — a modal cannot do that at
  all, and a sidebar section cannot hold a log.

A modal blocks the canvas. A pushing drawer or a second sidebar costs canvas
width on every Map, for content the therapist opens now and then. A
**floating window** costs nothing until opened, and the therapist can drag it
over the empty region of their Map.

The macOS HIG retired floating utility panels for document-specific content
in favour of inspectors and popovers; Colours and Fonts survive because they
are app-wide. This decision is a deliberate step off that path: the
user-placement benefit over a live canvas outweighs it. The cost is kept low
by making the primitive small and its rules few.

## Decision

Add one **floating window** primitive to the map view, and move the three
surfaces above onto it.

### The primitive

- **Non-modal.** It is the existing `<dialog>` element opened with `show()`,
  not `showModal()`: no backdrop, the page stays live, Escape still closes
  via the `cancel` event. Nothing is invented for blocking or focus.
- **One window per kind.** A kind (body location, trigger, statements) has at
  most one window. Opening it again brings it to front.
- **Drag by title bar**, with pointer events (mouse and iPad). The position
  is clamped to the map view on drag and on viewport resize. Position is kept
  for the page's life only; a reload puts a window back at its default spot.
- **Default position per kind**, away from the inspector (top-right) and the
  tool palette (bottom-centre).
- **Fixed size per kind**, scrolling inside. No resize.
- **Click brings to front.** A single z counter, nothing more.
- **Escape closes** and one close button. The canvas key handler ignores keys
  whose target is inside a dialog, so Escape does one thing.
- **Mounted outside the ReactFlow pane**, over the map view, so a title-bar
  drag never reaches the canvas as a pan. Windows sit above the sidebar;
  covering the inspector is the user's choice.
- **Phones**: the map is view-only on a phone (TASK-105) and offers none of
  these surfaces, so no phone form is needed.

Deliberately **not** built: resize, minimise, several windows of one kind,
persisted position, snapping, cascade, keyboard move. Each waits for a
consumer that needs it.

### Scope following

A window shows the current entity of its **scope** and never pins:

| Kind | Scope | On empty selection |
|---|---|---|
| Body location | selected Part | closes |
| Trigger | viewed Session | n/a |
| Statements | selected Part, else viewed Session | switches to the Session view |

Selecting another Part changes what the window shows; it never leaves a
stale window behind. There is no pinned state to indicate, and the window
and the inspector cannot disagree. If a pin is ever wanted, revisit this
table.

Windows **survive time-travel**: while viewing a past Session they show that
Session's data read-only, with editing controls hidden. This falls out of the
same derived-Session reads the sidebar uses.

### Saving

Floating windows **autosave** like the sidebar: text commits on blur with
Escape reverting the field, discrete controls commit on change. A window that
stays open while the canvas is live cannot hold an uncommitted edit — on the
next selection change it would have to block, discard, or nag. So the trigger
loses its Save / Cancel; Escape-reverts covers Cancel. Body location already
commits on point change and loses nothing.

The "modals are the exception" clause of the saving model now covers only
**true modals**: the delete confirmation and the demo waitlist.

## Consequences

- **Body location first.** It is the smallest consumer with no data-model
  change and proves the primitive. Then the trigger. Self statements land on a
  primitive that has been in use.
- **Self statements need their own ADR** for the data model (a per-Part,
  bitemporal, append-only log with Session membership derived from
  `valid_at`, ADR-0014 style). This ADR fixes only the surface it renders in.
- **A window can hide nodes.** The user opened it and can drag it away; the
  default positions avoid the chrome, not the Map.
- **The modal component stays** for the two true modals.
