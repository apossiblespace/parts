# ADR 0012: Content-Security-Policy scoped to the authed surfaces

## Status

Accepted — 2026-06-15.

## Context

The `/app` SPA shell and the `/invite/:token` pages share the cookie-auth
origin with `/api` (ADR-0007). A third-party or injected script running on
those pages could read the `<meta name="csrf-token">` value and drive
authenticated, mutating `/api` calls — defeating both the httpOnly cookie and
the CSRF design from in-origin. No Content-Security-Policy was set at the app
or the Caddy layer.

The marketing, legal, and playground pages are a different case: they load the
Plausible analytics collector (a third party) and use inline `onclick` /
`hx-on:` event handlers. A strict `script-src 'self'` there would require
allowlisting Plausible and refactoring every inline handler — real work, on
pages that carry no auth cookie and reach no `/api` mutation.

`ring-defaults` has no CSP key, so a policy has to be added by custom code
wherever it lives.

## Decision

Ship a strict CSP — `script-src 'self'; frame-ancestors 'none'` — on the authed
surfaces only (`/app`, `/app{*path}`, `/invite/:token`), via a `wrap-csp`
middleware added to those routes. Marketing/legal/playground pages get no CSP
for now.

To make the authed surfaces clean under `script-src 'self'`:

- The Plausible queue stub moved inside the `analytics?` guard in
  `partials/head`, so it is emitted only on the marketing pages that load the
  collector — never on `/app` or `/invite`.
- The invite page's progressive-enhancement inline script (disable submit until
  valid) was removed; native HTML5 `required` validation and the server already
  gate the form.

App middleware, not Caddy: the policy is per-route and the routing lives in the
app. Caddy keeps the static, site-wide security headers (HSTS, Referrer-Policy,
X-Content-Type-Options, X-Frame-Options). `frame-ancestors 'none'` is the modern
equivalent of that X-Frame-Options DENY and coexists with it.

Outside prod the policy adds `'unsafe-eval'`: shadow-cljs dev builds load each
namespace via `eval`, which `script-src 'self'` blocks. The advanced prod build
is a single bundle with no eval, so prod stays strict.

## Consequences

- The marketing pages remain without a CSP — a deliberate, documented gap, not
  an oversight. Closing it (allowlist Plausible, refactor inline handlers to a
  site-wide or per-page CSP) is a separate follow-up.
- The invite form no longer disables its submit button until valid; it falls
  back to native validation. Acceptable degradation.
- A future page added under `/app` or `/invite` must not use inline script or
  load third-party script, or it will be CSP-blocked.
