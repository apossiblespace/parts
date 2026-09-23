# ADR 0018: Part conversation — a per-Part, append-mostly log of what was said

## Status

Accepted — 2026-09-22.

## Context

A therapist asked for "a way to represent Self in the map". Her workaround
was a fake Part labelled *Self* holding "Self's responses to the parts".

In IFS, Self is not a Part. *No Bad Parts* (Schwartz) is explicit: "Self is
not observable… it's the place from which you see your parts" (PDF p103);
seeing yourself in the inner world marks a Self-like *part* (p103, p120); in
the book's own mapping exercise only parts are drawn and Self is the viewer
(p36). So Self gets no node.

What the therapist is recording is the **conversation** between Self and each
Part, which the book frames as a relationship built over many visits:
"continue to deepen the relationship" (p34), "following up is crucial…
visit them for as long as it takes" (p184), "several sessions before
Parcher trusted me" (p161). It is two-way — parts answer, show, react —
and sometimes three-way: in **direct access** the therapist speaks to the
part directly (p55, p88–89, p160). The book's session transcripts are the
precedent for a speaker-labelled log, with bracketed action notes
("[Sam cries hard with relief]", p53).

This is *event* data, unlike notes or body location, which are *state*: a
past utterance stays true, so it accumulates rather than being overwritten.

## Decision

### The entity

A **Conversation entry** is one thing said or done in the conversation with
one Part:

| Field | Meaning |
|---|---|
| `id` | entry identity |
| `map_id` | owning Map (scope, as for Parts) |
| `part_id` | the Part the conversation is with — no DB FK, per ADR-0001 |
| `speaker` | `self` / `part` / `therapist` — all three from day one |
| `text` | free text: a quote, a reaction, an action |

It is **bitemporal** (ADR-0001): `valid_at`, `sys_period`, `actor_id`, the
EXCLUDE constraint and audit trigger, all temporal SQL confined to
`aps.parts.db.bitemporal`. Entries are ordered by the start of `valid_at`.

`text` is deliberately one free-text field. The book's exchanges are often
non-verbal ("see how it reacts", p33, p48); a bracketed note covers that
without a second field or an entry *kind*. Add a kind only if therapists
turn out to filter by it.

### Sessions

**Session membership is derived, never stamped** (ADR-0014): an entry
belongs to the Session whose `[anchor, next-anchor)` range holds the start
of its `valid_at`. No `session_id` column. Time-travel to Session N shows
the entries up to Session N for free.

### Append-mostly

- New entries always land in the **active** Session.
- An entry can be **edited or deleted only while its Session is the active
  one** — the typo window. No time-based window. Once a newer Session starts, the entry is part of the
  immutable past like everything else there (ADR-0014); the ops-level
  correction path is the only way to change it.
- Deleting a Part retracts its entries in the same all-or-nothing batch
  (ADR-0003), so no entry outlives its Part in the present. History keeps
  both.

### Writes

Three change-event types — create / update / remove — through the existing
change-event module and batch (ADR-0005). The update/remove backstop
rejects an entry whose Session is not the active one; the UI hides the
controls, the server is the judge. Two details keep that rule honest:

- The check takes a shared lock on the Map row until the batch commits;
  starting a Session takes it exclusively. A Session cannot start between
  the check and the write.
- A Session's anchor is stamped from the app server's clock, the same
  clock that stamps content writes, and kept strictly increasing. An entry
  written the instant a Session starts belongs to that Session.

### Data lifecycle

A clinical record, like notes and body location:

- **Render / PDF: excluded.**
- **Export: included** as a new top-level `conversation_entries` array in the
  ADR-0010 shape (id + versions). Additive, so `format_version` stays `"1"`.
- **Erasure: hard-deleted** with the Map, alongside Parts and
  Relationships.

### Surface

The **conversation window** — a floating window (ADR-0017):

- **Part mode** (scope: the selected Part): that Part's entries across all
  Sessions, grouped under Session headings, each entry labelled by speaker:
  "Self", "Therapist", or **the Part's own label** (e.g. "Firefighter") —
  read at render time, so renaming the Part relabels its past entries.
  A composer at the bottom with a three-way speaker control and **Send**
  (explicit, like every window). One draft per Part in window state.
- **Self mode** (scope: the viewed Session): that Session's entries grouped
  by Part — "what was said to the system this session". Read-only; a Part
  heading selects that Part on the canvas and flips the window to Part mode.
- Part mode follows the selection; an **empty selection switches to Self
  mode**. A mode toggle lets the user cross over.
- Entry points: the Part form shows the last few entries with an expand
  button (Part mode); the Session card opens Self mode.
- In Time-travel: read-only, composer hidden; the active Session's draft
  waits (ADR-0017 drafts are keyed by entity).
- UI copy says **Conversation**, not "Self statements": the book never
  uses that phrase, and the log holds more than two voices.

## Consequences

- One new bitemporal table (`conversation_entries`), three change-event
  types, one export key, one erasure step, one floating-window kind.
- The customer's fake *Self* Part becomes unnecessary; migrating her
  entries into the log is a manual, one-off conversation, not a feature.
- **Not in scope:** the per-Part "how do you feel toward it" state — the
  datum the book treats as primary (p32, p36). It is a relationship
  *state*, not an entry, and gets its own decision. Nor are the other
  per-Part records the book tracks (fears, protects, burden, age, updated,
  unburdened; see TASK-113.03 notes).

## Known limit: more than one Part in a conversation

The book shows exchanges beyond Self, the Part and the therapist: Self
mediating between two parts that both speak (p59–60), and inner figures such
as a dead relative or a "guide" (p123, p127). Here, each entry belongs to one
Part's conversation and its speaker is Self, that Part, or the therapist.
Another voice goes in bracketed text, or its own Part's conversation. If
therapists need it, the extension is additive: a speaker that can reference
any Part on the Map (`speaker_part_id`), with no change to existing rows.

## Resolved questions (2026-09-22)

1. **Name:** "Conversation" — it admits more than two participants.
2. **Edit window:** only while the entry's Session is active.
3. **Part speaker label:** the Part's own label.
