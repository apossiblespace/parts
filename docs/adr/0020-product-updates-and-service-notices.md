# ADR 0020: Product updates and Service notices from the Operator console

## Status

Accepted — 2026-10-09.

## Context

The operator wants to email Users from the **Operator console** (ADR-0019):
optional **Product updates** and required **Service notices** (see
`CONTEXT.md`). Parts had no opt-out concept and sent plain-text email only
(ADR-0016). Recipients are therapists, mostly sole traders, whom UK PECR and EU
ePrivacy treat as individual subscribers.

## Decision

**Subscription.** Every User is subscribed to Product updates by default,
existing Users included. At signup (self-serve and Invitation redemption) a
"Send me product updates" checkbox is ticked by default, set apart from the
required acceptance checkboxes by a rule; unticking it opts the new User out.
It is phrased positively, not as "Do not send me…", because people tick the
required boxes in a row and would tick an opt-out one by habit. Any User can
opt out on the Account page or through the unsubscribe link. Service notices
ignore the opt-out.

**Opt-out storage.** `users.product_updates_opted_out_at` (NULL = subscribed;
the timestamp is evidence of when) and `users.unsubscribe_token`, a random UUID
like the Invitation token. No signing key, so no key rotation can break links
already sent.

**Unsubscribe link.** `/unsubscribe/<token>` on the public app, never expires.
GET shows a confirm page and changes nothing, because corporate mail scanners
(e.g. Microsoft Safe Links) fetch every link. POST unsubscribes; the token is
the authentication, so no CSRF token. `List-Unsubscribe` and
`List-Unsubscribe-Post` (RFC 8058) point at the same POST URL.

**Format.** `multipart/alternative`: `markdown-clj` + the OWASP sanitizer (as
in `legal.clj`) in a minimal inline-CSS template as `text/html`, and the same
sanitised content as `text/plain` (`legal/render-text`): no Markdown markers,
links written inline as "text (url)" rather than as footnotes, because these
emails are short and carry few links. Sent with `send-personal!`. No template variables. No
open or click tracking. A fixed footer is appended: why the User gets the email
and their address ("sent to …"), an unsubscribe link on Product updates, a
Privacy Policy link, and the company line. Parts is run by a UK limited
company, so UK company law requires its registered name, number, registered
office and VAT number in business emails; the line comes from
`PARTS__MAIL__SENDER_IDENTITY`, so the open-source repository holds no company
details.

**Sending.** A test send goes to the operator with a `[Test]` subject prefix and
a dummy unsubscribe link. Send-to-all is enabled only for the exact draft (hash
of kind + subject + body) that was test-sent. Recipients: the Fleet, minus
accounts pending deletion, minus opted-out Users for Product updates.

**Records.** `operator_emails` (what was sent) and `operator_email_deliveries`
(one row per recipient, written after SMTP accepts, with any error). The rows
are evidence that a User was notified (the ADR-0009 reasoning) and make an
interrupted send resumable: resume targets Users with no delivery row. Erasure
deletes a User's delivery rows.

## Considered options

- **Unsubscribed by default for existing Users.** Legally cleaner, because
  existing Users had no chance to refuse at signup. Rejected by the project
  owner in favour of reach; the unsubscribe path in every Product update is the
  mitigation.
- **Plain text only.** No rendering code, but a weak preview and raw links.
- **HMAC-signed unsubscribe links.** No column, but a key to manage, and
  rotating it breaks every link already sent.
- **A summary row per send, no per-recipient rows.** Cannot resume an
  interrupted send and cannot show who was notified.

## Consequences

- `docs/data-inventory.md` gains the two `users` columns and the deliveries
  table. The privacy policy (parts-ops) should mention Product updates and the
  opt-out.
- No saved drafts, scheduled sends, or audiences. Add when missed.

## Addendum (2026-10-09): shared layout for transactional emails

The layout, the HTML document and the footer rendering moved from
`aps.parts.operator-email` to `aps.parts.email-layout`, so transactional emails
to Users use the same rendering path as Operator emails (TASK-142.01). The
password reset email now has the same HTML and plain-text parts, and a footer
with the recipient's address ("This email was sent to … about your Parts
account."), the Privacy Policy link and the company line. A transactional
email has no unsubscribe link and no `List-Unsubscribe` headers. Operator
email output did not change. Operator alerts stay plain text without a footer:
they go to the operator, not to Users. The subscription thank-you email is not
moved yet, by the project owner's decision.
