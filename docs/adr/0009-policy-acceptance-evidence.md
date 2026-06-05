# ADR 0009: Policy-acceptance records as write-once, server-stamped evidence

## Status

Accepted — 2026-06-05.

## Context

Onboarding has to capture that each therapist agreed to the Privacy Policy,
Terms of Service, and Data Processing Agreement, and attested that they use
Parts for private-pay practice (task-009 AC #4, #5, #8, #10). This is not
decorative: the therapist is the **controller** of their clients' data and
Parts is the **processor**, and the DPA is the document that allocates that
split. If the allocation is ever disputed, GDPR Art. 5(2) accountability means
we must be able to *show* — not merely argue — that a given therapist accepted
a given version of the DPA.

Some structure already constrains the design:

- **One creation chokepoint.** Both the live invite flow (`invite/redeem`) and
  the post-launch self-serve flow (`account/register-account`) funnel through
  `api.account/provision-account!`, which runs inside a single
  `db/with-transaction`.
- **Acceptance is mandatory**, so account-exists ⟹ accepted-everything. The open
  question is what, if anything, to persist *beyond* the account's own
  existence.
- **Documents are versioned independently** by front-matter; `legal/document`
  returns the current `:version` per slug (`privacy`/`terms`/`dpa`).
- **Erasure hard-deletes the user.** `db.erasure` is the only namespace that
  issues `DELETE FROM`; it spells out a delete for every table of a user's
  content and finishes with `DELETE FROM users`. It relies on no cascades.

## Decision

Persist onboarding acceptance as **write-once evidence**: a `policy_acceptances`
table, one append-only row per `(user, document, version)`, written inside the
same transaction that creates the user.

```sql
CREATE TABLE policy_acceptances (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID NOT NULL REFERENCES users(id),
  document    TEXT NOT NULL,        -- 'privacy' | 'terms' | 'dpa'
  version     TEXT NOT NULL,
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, document, version)
);
```

Supporting decisions:

- **Evidence, not consent.** Agreeing to the Terms/DPA is contract formation and
  the private-pay line is an attestation/warranty — neither is GDPR Art. 6(1)(a)
  consent, which is opt-in and *withdrawable*. So the table is
  `policy_acceptances`, not `consents`: it has no withdrawal semantics and is
  **never read to gate access** (account existence does that). Modelling it as
  withdrawable consent would wrongly imply a user can "un-agree" to the Terms.
- **Store the version, not a boolean.** The only thing account-existence does
  *not* capture is *which version* was agreed. That is the entire value of the
  record; the redundant "accepted?" flag is simply not stored.
- **The server stamps the version.** The client sends only two booleans ("I
  checked the boxes"); the server records the *current* version of each document
  from `legal/document`. A forged or replayed POST therefore cannot fabricate
  agreement to a version that was never shown.
- **Per-document rows.** Each accepted document is one uniform
  `(user, document, version)` fact — `privacy`, `terms`, `dpa`. Three rows per
  signup. The documents version independently, and re-acceptance later is just
  "insert another row" — never a mutation or schema change.
- **Private-pay scoping lives in the Terms of Service, not a separate row.** The
  form has a second, required *medical-data acknowledgement* checkbox (label:
  `aps.parts.common.constants/medical-data-notice`), but it is a gate, not a
  recorded acceptance — the Privacy Policy already discloses the processing, and
  the private-pay warranty is a ToS clause bound by the legal-docs acceptance
  above. Promoting it to a separate timestamped attestation (AC #8) was
  considered and deferred — see Alternatives.
- **`UNIQUE (user_id, document, version)`.** One acceptance per version. Its
  composite index leads with `user_id`, so it also serves the erasure delete and
  the future "what has this user accepted?" lookup — no separate index needed.
- **Enforced in `provision-account!`.** The shared path validates both booleans
  (a `:validation` error otherwise, reusing `redeem`'s re-render-with-error
  path), then writes the rows via a new `entity/policy-acceptance` in the same
  `tx`. A single chokepoint means *every* creation path inherits "no account
  without its acceptance rows."
- **Deleted on erasure.** An explicit `DELETE FROM policy_acceptances WHERE
  user_id = ?` is added to `db.erasure` before the user delete, matching the
  namespace's spell-out-every-table style.
- **No IP / User-Agent.** The acceptance is already bound to an authenticated
  `user_id`; IP/UA earns its keep only for anonymous acceptance. Capturing it
  here would pour fresh personal data into a new table against the
  data-minimisation stance held elsewhere (e.g. task-049).

## Alternatives considered

- **Persist nothing; rely on `users.created_at` + deploy history.** At launch
  there is one version of everything, so the agreed version is inferable from the
  signup date. Rejected: it ties the DPA's evidentiary record to archaeology
  across a *separate private repo's* git/deploy history, reconstructed after the
  fact — weak evidence for special-category data, and ambiguous the moment any
  document revises.
- **A single bundle row** (`privacy_version`, `terms_version`, … columns, or a
  JSON blob, one row per signup). Rejected: it braids the independent facts into
  one record, handles divergent versions awkwardly, and turns any future
  re-acceptance into a schema change or an in-place mutation.
- **A separate, explicit private-pay attestation row** (a 4th `document =
  'private-pay'` row — the medical-data checkbox recorded as its own timestamped,
  versioned attestation, AC #8). Considered and deferred for the concierge
  launch: the private-pay warranty is already binding as a Terms-of-Service
  clause accepted via the legal-docs checkbox, and at invite-only, personally
  vetted scale the extra evidentiary prominence buys little against the signup
  friction — a forced attestation also wrongly implies *every* user records
  *client* data, when self-work and experimentation are valid uses. Re-elevate if
  Parts opens to self-serve or US covered entities, where an active, prominent
  confirmation of private-pay status earns its keep.
- **A `consents` table with withdrawal columns.** Rejected: mis-models
  contractual acceptance as withdrawable GDPR consent (see above).
- **Retain acceptances post-erasure for legal defence.** Rejected: it fights the
  hard-delete erasure model (the user row is removed, so the FK would dangle),
  and the only privacy-clean way to keep a row — reassigning `user_id` to the
  shared tombstone, as `audit_log` does — destroys the very who-agreed link that
  is the evidence. Once the user and their Maps are erased, the DPA those
  acceptances governed is moot, and Parts is not a system of record.
- **Capture IP/User-Agent for stronger evidence.** Rejected (above): marginal
  gain over an authenticated `user_id`, real PII cost.
- **Wire both account-creation paths now.** Rejected: `register-account` is
  `wrap-launch-gated` *and* its SPA `/app/signup` redirects to login pre-launch,
  so it is doubly unreachable at a concierge launch. The shared enforcement
  already protects it; building its checkbox UI now is speculative and will want
  its own design when self-serve actually ships.

## Consequences

- Acceptance is provable per-user, per-document, per-version with one trivial
  query, decoupled from deploy-history reconstruction — exactly where the DPA's
  controller/processor split most needs clean evidence.
- The table is **insert-only and unread at launch**: no gating, no consent UI, no
  withdrawal flow — minimal runtime surface for a launch-blocking requirement.
- **Re-acceptance (Privacy Policy §12) costs no schema change.** When a document
  revises, a user accepts the new version → a new row; "needs re-acceptance?" is
  "the current version is absent from this user's accepted versions for that
  document." The mechanism is designed-in but unbuilt.
- `db.erasure` gains one more table to delete, and its test must now enumerate
  `policy_acceptances` — the erasure surface stays auditable in one place.
- `provision-account!` gains a dependency on the legal loader (it reads doc
  front-matter at signup to resolve current versions). Negligible at signup
  volume; it is the price of stamping authoritative versions server-side.
- The medical-data acknowledgement is a required gate with no recorded row, so
  its wording (the `medical-data-notice` constant) carries no version — the
  Privacy Policy acceptance is the dated record that the disclosure was shown.
- A forged or replayed signup POST can neither fabricate acceptance of an unseen
  version (server-stamped) nor create an account without acceptance (shared
  enforcement) — the two failure modes that would undermine the evidence are
  closed by construction.
