# ADR 0016: Scaleway TEM for transactional email

## Status

Accepted — 2026-07-19.

## Context

The concierge launch deliberately shipped without transactional email
(TASK-014): invitations go out through the operator's personal SMTP
(`ops/send-invitation-email!`, over Fastmail), and password resets are
concierge. Self-serve flows are being planned (password resets, billing),
and nearly all of them need a mail layer first.

Volume is tiny and will stay tiny for a long time: ~90 users; sends are
password resets plus occasional invitation cohorts — dozens of emails a
month, not thousands. At this volume deliverability is dominated by domain
authentication (SPF/DKIM/DMARC on the sending domain), which is identical
work for every provider; provider polish matters far less than it would at
scale.

Two EU candidates were compared: **Scaleway TEM** and **Lettermint**. The
trade is the classic infra-vendor-vs-focused-SaaS split. Lettermint (and
dedicated ESPs generally — Postmark, Resend) wins everything you *touch*:
purpose-built dashboard with per-message event timelines, cleaner docs,
direct founder support, deliverability as its core business. Scaleway wins
everything you *depend on*: infra-grade company longevity, pay-per-email
pricing with no monthly floor (~300 free/day), and it is **already a
sub-processor** — it holds the age-encrypted off-box backups
(data-inventory §3), so no new vendor, DPA, account, or billing
relationship.

The planned `aps.parts.mail` wrapper deliberately shrinks the touch
surface to one seam, which blunts the focused-ESP advantages: after setup,
the provider is interacted with mainly when something bounces.

## Decision

**Scaleway TEM**, wired as follows:

- **`aps.parts.mail`** wraps the provider behind a `send!` taking a
  postal-style message map. Consumers never see provider or transport.
- **Transport is SMTP relay via `postal`** (already a dependency, already
  proven in the concierge mailer). The HTTP API is deliberately not used
  yet — see the revisit trigger.
- **Sending domain: `ifs.tools`**, verified in TEM with SPF/DKIM/DMARC. A
  reputation-isolation subdomain was rejected as over-engineering at this
  volume. Invites go From `Gosha <gosha@ifs.tools>` with Reply-To
  `gosha@gosha.net`, so replies still land with a human. **No `no-reply@`
  addresses** — they would contradict the concierge ethos.
- **`aps.parts.alerts` re-points its SMTP config at the Scaleway relay**
  but remains a closed alert sink (its design note stands); only the relay
  underneath changes.
- **Config**: `conf/smtp-config` is split from the alert recipient — relay
  credentials (`PARTS__SMTP__*`) stop requiring `:alert/to` to be present,
  and `:mail/from` / `:mail/reply-to` keys are added. Switching relays is
  then a values-only change in the environment.
- **Bounce / suppression handling is deliberately minimal**: Scaleway's
  provider-side blocklists (automatic after hard bounces) plus the TEM
  console. No webhook endpoint — Scaleway routes webhooks through its
  Topics & Events product, plumbing that is not justified for a
  low-volume, operator-attended sender whose recipients are hand-vetted
  waitlist signups.

## Alternatives considered

- **Lettermint.** Best-in-class DX, simpler bounce eventing, same-day
  founder support, EU (Netherlands). Rejected on: young-vendor longevity
  risk for what becomes a load-bearing auth path, a €10/mo floor beyond
  the small free tier vs effectively €0 forever, and a brand-new
  sub-processor row + DPA where Scaleway is an amendment to an existing
  one.
- **HTTP API transport.** The API returns a message ID whose delivery
  status is queryable — a better bounce story than SMTP. Deferred, not
  rejected: SMTP reuses the proven postal path today, and the wrapper
  seam makes a transport swap invisible to consumers.
- **Webhook bounce eventing.** Converts delivery failures from pull to
  push; pays off in proportion to volume and how unattended sends are.
  Both are near zero here today.

## Consequences

- **data-inventory §3 needs two amendments**: the Scaleway row changes
  from "cannot read anything (age-encrypted backups)" to also seeing
  **transactional email plaintext** (recipient addresses, message
  bodies); the Fastmail row narrows to operator correspondence (it no
  longer carries invites or alerts).
- **`ifs.tools` DNS** gains TEM records. If an SPF record already exists,
  Scaleway's include must be **merged into it** — a second SPF record on
  the same domain is an instant deliverability failure. DKIM/DMARC are
  additive.
- The invite template's line "I'm sending this from my personal email
  address" needs a light edit; the reply promise survives via Reply-To.
- Future **client invites** (planned per CONTEXT.md) will route therapy
  clients' email addresses through Scaleway — sensitive-adjacent data.
  EU processor, acceptable, but the privacy policy should say it.

## Revisit trigger

When unattended sends ship (self-serve password reset), the pull model
stops being adequate: a bounced reset email is invisible until the user
gives up. At that point reconsider the HTTP API transport (message-ID
status queries from a REPL helper) or webhook eventing. Because of the
`aps.parts.mail` seam, either is a transport change, not a redesign.
