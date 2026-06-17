# Parts — domain & architecture glossary

The shared vocabulary for this codebase. New decisions go in `docs/adr/`; this file is the day-to-day language.

## Product

**Parts** is a tool for therapists practicing **IFS** (Internal Family Systems) therapy and their clients. The first version allows a therapist to build a **Map** of a client's inner landscape — a Map of **Parts** (manager, firefighter, exile, unknown) connected by **Relationships** (protective, polarization, alliance, burden, blended, unknown).

**Parts** doesn’t aim to be used during a therapy session; this time is best left as device-free as possible, so that the therapist and the client are both free to engage with the therapeutic process. Instead, it aims to support the therapist in the work that happens in between sessions: keeping track of what was uncovered during a session and facilitating review before the next session.

### Features planned for post-launch include:

- Being able to print out a Map to share with the client, as support for homework
- Allowing the client to access their Map and interact with it with various levels of permission (viewing only, editing, etc)
- Managing multiple Maps for multiple clients of a therapist
- Allowing a client to transfer their Map to another therapist if, for example, they pause their therapeutic journey and restart it with another therapist later
- A Scrubber that allows a therapist or client to time-travel through a Map’s evolution session-over-session

### Important considerations

**Parts** is a product dealing with highly sensitive data about clients’ mental health. Decisions made should be highly conservative and biased towards boring/reliable/proven technologies, especially when it comes to data storage and security.

Data safety and integrity is a paramount concern while developing this application.

We should also be mindful of possible future legal requirements for tools dealing with mental health data. We don’t need to implement full compliance from the start, but we should avoid making decisions that will make full compliance more difficult in the future.

UI decisions defer to the **macOS Human Interface Guidelines**. Parts aims for UI quality in the tradition of the best macOS applications — sometimes aspirational, but when an interaction or visual question has no project-specific answer, the HIG is the tie-breaker. (Example: menu checkmarks sit in a fixed leading column, not trailing.)

## Domain entities

- **User** — anyone with an account. Today the only role is **therapist**; **client** is a planned future role with its own access scope.
- **Map** — a Map owned by a User. The Parts and Relationships inside it represent one client's IFS mapping. A Map has *identity* (id, owner_id) that doesn't change, and *metadata* (title; future: sharing, permissions, client_id) that does. The two live in different tables; see ADR-0002.
- **Part** — one of the components of a Map. Has a `type` (manager / firefighter / exile / unknown), a label, a position on the canvas, and optional notes / **body location**. Bitemporal: every attribute change writes new history rows.
- **Body location** — where in the client's body a Part is felt (its somatic locus). Optional, per-Part, clinical. The therapist pinpoints it on a body **silhouette** (a front figure and a back figure). Stored as a *structured point* — which figure (`view`: front / back) plus normalized coordinates within that figure — **not free text**. A future *region* code (e.g. `shoulder-left`) may be derived from the point to produce a human-readable, localizable description; the code, never an English label, is what gets stored, so the record stays language-neutral and survives re-authoring of the region map. Like `notes`, it is a clinical field: excluded from **Render**, included in **Export**.
- **Relationship** — a directed edge between two Parts. Has a `type` (protective / polarization / alliance / burden / blended / unknown) and optional notes. Bitemporal.
- **Therapist** — current User role. Owns Maps.
- **Client** *(planned)* — future User role with restricted access to a Therapist's Map.
- **Session** *(implicit)* — a therapy session. Not modeled as an entity yet — sessions are implied by clusters of changes in the scrubber's timeline. May become explicit later.
- **Invitation** — an operator-minted, single-use bearer credential authorising creation of one account. The `token` in a magic link (`/invite/<token>`) is the credential. Lives in its own non-temporal `invitations` table, deliberately separate from `waitlist_signups`: an Invitation may be issued to an email that never joined the waitlist, and a token is a credential, not a marketing signal. Lifecycle: issued → redeemed | revoked (soft, via `revoked_at`). No expiry. Operator tooling and redemption live in `aps.parts.invitations`.
- **Founding Circle** — the first cohort of practitioners, onboarded by Invitation during the concierge launch. `users.is_founding_circle` marks membership; it is carried from `invitations.is_founding_circle` when the Invitation is redeemed.
- **Paid-through date** — `users.paid_through_date` (a `DATE`; NULL means never paid) records how far a User's account is paid up. The concierge launch bills out of band — a Stripe invoice the operator sends by hand — and the `aps.parts.billing` operator helpers move the date once an invoice clears. The date is *recorded* only: access is not gated on it yet (a deliberate later step).

## Architectural terms

- **Bitemporal layer** — the seam at `aps.parts.db.bitemporal`. Owns all temporal SQL for tables that record history (`parts`, `relationships`, `map_metadata`). See ADR-0001.
- **Scrubber** — the planned UI feature for replaying a Map at any past moment. Reads via `bt/as-of-valid`.
- **Render** *(planned)* — server-side SVG of a Map's Parts and Relationships, used for thumbnails on the Maps list and for PDF export (SVG transcoded). Renders **structure only** by default: Part shapes, labels, Relationship lines. Clinical fields (`notes`, `body_location`) are deliberately excluded because the PDF is a client-facing hand-out. Composes with the **Scrubber** at any `valid_at`. Planned home: `aps.parts.render`.
- **Export** *(planned, task-042)* — a machine-readable copy of a Map's full history, for the data subject's right of access / portability (GDPR Art. 15 / 20) and the ToS promise that a therapist can export their Maps at any time. Unlike **Render** (a structure-only client hand-out), the Export is the *complete* copy — it deliberately **includes** the clinical fields (`notes`, `body_location`). It carries the **valid-time** history only (every version and when it was true), not the transaction-time record of our own corrections. Planned home: `aps.parts.export`.
- **`audit_log`** — trigger-populated table that records every change to a temporal table. *Committed-only*: failed batches leave no audit row. See ADR-0003.
- **Tombstone user** — the placeholder User with id `00000000-…` that owns audit rows attributed to a since-deleted User. Lets FKs on `audit_log.actor_id` stay enforced after erasure. Inserted by migration `20260511000000`. See ADR-0004.
- **All-or-nothing batch** — when the API applies a list of changes for a Map, either all of them commit or none do. See ADR-0003.
- **Change-event** — the intent to mutate a Map's contents: `{:entity :type :id :data}`. Owned by the `aps.parts.common.change-event` module — a `.cljc` seam shared client/server, with named constructors for producers and a `parse` trust gate for consumers. Covers *committed* mutations only — what goes through the all-or-nothing batch into the bitemporal record. `:position` is not a type; a canvas move is an `:update` (built by the `part-moved` constructor). See ADR-0005.
- **Presence** *(planned)* — the future channel for *ephemeral* multiplayer data: live drag frames, cursors, remote selections. Deliberately **not** a change-event — lossy, last-write-wins, gone on disconnect. See ADR-0005.
- **Direct-manipulation canvas** — the map canvas has no modes: drag a Part's interior to move it, drag its boundary ring to connect, drag empty canvas to pan. The toolbar only chooses what creation produces (Part type to place, Relationship type to draw with). See ADR-0011.
- **Identity vs. metadata** — design split where a record's *identity* (immutable identifying columns) lives in one table and its *metadata* (changing attributes) lives in another bitemporal table. Used for `maps` + `map_metadata`. See ADR-0002.
- **Map access** — the rule that scopes the single-Map routes (`GET/PUT/DELETE /api/maps/:id`, plus `/changes` and `/pdf`) to the Map's owner. Implemented as a Reitit route middleware (`wrap-map-access` in `aps.parts.auth.middleware`) that runs before the handler — so handlers on those routes don't self-check ownership. Missing and not-owned both render as 404. See ADR-0006.
- **Erasure** — the right-to-be-forgotten flow at `aps.parts.db.erasure`. The *only* code path that issues `DELETE FROM` on temporal tables. Enforced by an architecture-fitness test.
- **Architecture-fitness test** — `test/aps/parts/architecture_test.clj`. Greps source files to verify that temporal vocabulary and `DELETE FROM` on temporal tables only appear in their designated namespaces.

## Vocabulary from outside

- **IFS** — Internal Family Systems, a therapy methodology developed by Richard Schwartz. The named Part types (manager, firefighter, exile) come from this framework.
- **Bitemporal** — recording both *valid time* (when a fact was true in the world) and *transaction time* (when we recorded it). The canonical reference is Snodgrass, *Developing Time-Oriented Database Applications in SQL*.
- **Sequenced / current / nonsequenced** — Snodgrass's classifier for temporal operations. Our writes are sequenced; our readers are time-slice.
