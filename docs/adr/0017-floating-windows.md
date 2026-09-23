# ADR 0017: Floating windows — non-modal, map-scoped editors

## Status

Accepted — 2026-09-13.

Amends the **saving model** in `CONTEXT.md`: sidebar fields autosave;
floating windows and modals commit explicitly.

Revised 2026-09-14: windows hold drafts with Save / Discard changes (not autosave),
resize is in v1, and Part notes is a fourth consumer.

## Context

Four surfaces need more room than a sidebar form gives, but must not stop
the therapist from working on the canvas while they are open:

- **Body location** (ADR-0013): the two-figure silhouette. Today a modal.
- **Session trigger** (ADR-0014): a multi-line text. Today a modal with
  Save / Cancel.
- **Part notes**: a textarea in the sidebar, cramped for anything long.
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

Add one **floating window** primitive to the map view, and move the four
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
- **Resize by a corner handle**, pointer events again (CSS `resize` draws no
  grip on iOS). A minimum size per window, and never past the map view. Space
  is at a premium on a 13" laptop or an iPad; the therapist arranges the
  workspace.
- **Click brings to front.** A single z counter, nothing more.
- **Escape closes** and one close button. The canvas key handler ignores keys
  whose target is inside a dialog, so Escape does one thing.
- **Mounted outside the ReactFlow pane**, over the map view, so a title-bar
  drag never reaches the canvas as a pan. Windows sit above the sidebar;
  covering the inspector is the user's choice.
- **Phones**: the map is view-only on a phone (TASK-105) and offers none of
  these surfaces, so no phone form is needed.

Deliberately **not** built: minimise, several windows of one kind,
persisted position or size, persisted drafts, snapping, cascade, keyboard
move. Each waits for a consumer that needs it.

### Scope following

A window shows the current entity of its **scope** and never pins:

| Kind | Scope | On empty selection |
|---|---|---|
| Body location | selected Part | closes |
| Notes | selected Part | closes |
| Trigger | viewed Session | n/a |
| Statements | selected Part, else viewed Session | switches to the Session view |

Selecting another Part changes what the window shows; it never leaves a
stale window behind. There is no pinned state to indicate, and the window
and the inspector cannot disagree. If a pin is ever wanted, revisit this
table.

Windows **survive time-travel**: while viewing a past Session they show that
Session's data read-only, with editing controls hidden. This falls out of the
same derived-Session reads the sidebar uses.

### Saving: drafts with Save / Discard changes

A window is where the therapist **composes**. Sidebar fields are quick edits
and autosave on blur; a window must not — clicking the canvas mid-sentence
must not commit, and the writer must not have to think about it. So:

- A window edits a **draft**, keyed by kind and scope entity, held in window
  state for the page's life. **Save** commits through the entity's normal
  update path (the map status indicator takes over from there). **Discard
  changes**
  discards the draft.
- **Close and Escape keep the draft.** Reopening shows it. Discard changes is
  the only discard.
- **One footer for every editing window:** the save status on the left
  ("Unsaved changes" with a dot; empty when clean), Discard changes and Save
  on the right. Controls that *edit* the draft stay with the content, never in
  the footer (Body location's Remove pin sits in the corner of the figure that
  holds the pin).
- Because drafts are keyed by entity, selecting Part B and returning to Part
  A shows A's draft again, and in Time-travel the active Session's trigger
  draft waits while the past is shown read-only.
- While a field's window is open for an entity, the sidebar's quick editor
  of that same field is disabled, so two editors never race.
- Body location follows the same rule (place a pin, then Save). It is the one
  place the draft model costs a click; if that proves heavy in use it can go
  back to commit-on-place without touching the others.

The saving model in `CONTEXT.md` becomes: **sidebar fields autosave; floating
windows and modals commit explicitly.** A draft that was never saved is not a
sync state — the status indicator reports backend sync only.

## Consequences

- **Body location first.** It is the smallest consumer with no data-model
  change and proves the primitive. Then the trigger, then notes. Self
  statements land on a primitive that has been in use.
- **The sidebar form commits only changed fields** and re-syncs fields that
  are not being edited when the entity changes underneath it. Without that, a
  window's notes save would be written back by the next label blur.
- **Self statements need their own ADR** for the data model (a per-Part,
  bitemporal, append-only log with Session membership derived from
  `valid_at`, ADR-0014 style). This ADR fixes only the surface it renders in.
- **A window can hide nodes.** The user opened it and can drag it away; the
  default positions avoid the chrome, not the Map.
- **The modal component stays** for the two true modals.
