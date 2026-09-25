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

### Editing — the same as notes

- New entries always land in the **active** Session.
- An entry can be **edited or deleted from the present at any time**, as a
  Part's notes can. Writes are sequenced (ADR-0001): an edit closes the
  current version and starts a new one from now, so Time-travel to an
  earlier Session still shows what was written then. In Time-travel the
  canvas is read-only and no editing controls show.
- Deleting a Part retracts its entries in the same all-or-nothing batch
  (ADR-0003), so no entry outlives its Part in the present. History keeps
  both.

*Revised 2026-09-24.* The first version allowed edits only while the
entry's Session was active. It needed a server check, a Map-row lock
against a concurrent Session start, and a second copy of the rule on the
client — to protect what sequenced writes already protect. It was removed.
What remains open is presentation: an entry edited after its Session reads,
in the present, as if written in that Session. That question applies to
notes and every other sequenced clinical field alike, and is tracked
separately rather than solved for entries alone.

### Writes

Three change-event types — create / update / remove — through the existing
change-event module and batch (ADR-0005), scoped to the Map like Parts and
Relationships. A Session's anchor is stamped from the app server's clock,
the same clock that stamps content writes, and kept strictly increasing, so
an entry written the instant a Session starts belongs to that Session.

### Data lifecycle

A clinical record, like notes and body location:

- **Render / PDF: excluded.**
- **Export: included** as a new top-level `conversation_entries` array in the
  ADR-0010 shape (id + versions). Additive, so `format_version` stays `"1"`.
- **Erasure: hard-deleted** with the Map, alongside Parts and
  Relationships.

### Surface

The **conversation window** — a floating window (ADR-0017), laid out as a
log viewer, not a chat app: reading comes first, adding second. Entries
are left-aligned in runs, the speaker's name once above each run:
"Self", "Therapist", or **the Part's own label** (e.g. "Firefighter") —
read at render time, so renaming the Part relabels its past entries. A
Part name carries a dot in its type colour; the name itself stays in
the label colour, because the type colours fail text contrast.

- **Part mode** (scope: the selected Part), titled "Conversation:
  <label>": that Part's entries across all Sessions, under sticky Session
  headings. Opens at the newest entry. A composer at the bottom: a
  three-way speaker control, the text, and **Add** (Return adds). The
  speaker stays as chosen. One draft per Part in window state.
  Double-click an entry to edit it in place; Delete is in that editor.
- **Self mode**, titled "Conversation": all Sessions too, grouped by Part
  inside each Session. It opens at the top of the viewed Session, so the
  Session card still lands on "what was said to the system this
  session", but the Sessions before it are a scroll away, as in Part
  mode. Read-only; a Part heading selects that Part on the canvas and
  flips the window to Part mode.
- Part mode follows the selection; an **empty selection switches to Self
  mode**. A mode toggle lets the user cross over.
- Entry points: the Part form shows the last few entries with an expand
  button (Part mode); the Session card opens Self mode.
- In Time-travel: read-only, composer hidden; the active Session's draft
  waits (ADR-0017 drafts are keyed by entity). The log is the snapshot's,
  so entries after the viewed Session do not show.
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
2. **Edit window:** only while the entry's Session is active. *Superseded
   2026-09-24: entries edit like notes; see "Editing".*
3. **Part speaker label:** the Part's own label.
