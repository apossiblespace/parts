# ADR 0021: A free trial on our own clock, and read-only when lapsed

## Status

Accepted — 2026-10-13.

## Context

Parts launched as a free beta with optional self-serve Stripe subscriptions
(TASK-046). Payment now becomes required (TASK-158). Two questions decide how
that feels to a therapist: how a new account starts, and what happens when an
account has no payment behind it.

The audience is small, careful about trust, and holds clinical records of
their clients. The value of Parts shows only after a few weeks of real
sessions: the work happens between sessions (`docs/pricing.md` decision 4).
The Terms already promise that a lapse never deletes data, and decision 7 says
a lapse must not become a clinical-continuity problem.

## Decision

**A 30-day free trial on our own clock, with no card.** A trial ends 30 days
after the account was created, but never before the **payment start date** (a
config value). Beta accounts therefore keep free use until that date, and an
account created near it still gets 30 days. No Stripe customer exists until
the therapist chooses to subscribe.

**One entitlement rule.** An account can edit while it is in its trial, or has
a live subscription (active, cancelling at period end, or past due while Stripe
retries the payment), or has paid time left. Otherwise it is **lapsed**. The
grace period after a failed payment is Stripe's retry window, not a clock of
ours.

**Lapsed means read-only, never locked out and never deleted.** A lapsed
account cannot change Map content, start a Session, or create or rename a Map.
It can still sign in, open and time-travel every Map, download every export and
all its data, archive and unarchive, and use the whole Account page. Paying
again restores editing at once.

**Founding Circle gets no automatic benefit** (decision 3 stands). Thank-you
comps are set by hand on the paid-through date.

## Alternatives considered

- **A Stripe trial with a card at sign-up.** Rejected. It turns more trials
  into payments, through an automatic charge, but fewer therapists would start;
  every sign-up would become a Stripe customer (a Privacy Policy change for
  people who never pay); and read-only would still be needed for anyone who
  abandons Checkout, so it adds code instead of saving it. Surprise charges also
  cost goodwill with a small professional audience.
- **A Stripe trial without a card** (`payment_method_collection: if_required`).
  Rejected: all of the Stripe-customer and webhook costs, none of the
  conversion benefit.
- **A fixed grace period of our own after a failed payment** (for example 7
  days). Rejected: it can cut access while Stripe is still collecting, or keep
  it after Stripe has given up.
- **Locking a lapsed account out, or suspending it.** Rejected: therapists
  would lose access to clinical records because of billing. Read-only keeps
  every record available.
- **A fresh 30-day trial for beta accounts from the start date.** Rejected for
  one rule that gives everyone a known end date.

## Consequences

- One predicate (`can-edit?`) drives the server guard, the Map strip, the Maps
  list banner and the Billing section.
- Trial reminders are ours to send (an hourly job, 7 days before the end and on
  the first read-only day, because the last trial day is still an editing day;
  beta accounts only the 7-day one). Failed payments rely on Stripe's customer
  emails.
- The Terms say read-only concretely and no longer list non-payment as a reason
  to suspend an account, so the policy cannot quietly become a lock-out later
  without a Terms change.
- Revisit if many trials end without a subscription and sign-up volume is high:
  adding a card at sign-up is an addition to this design, not a rewrite.
