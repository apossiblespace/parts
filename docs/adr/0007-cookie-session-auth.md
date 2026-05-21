# ADR 0007: Cookie-based auth session

## Status

Accepted — 2026-05-22.

## Context

The SPA authenticated with two JWTs — a 15-minute access token and a 30-day
refresh token — returned in the login JSON body and stored in `localStorage`
(`parts-auth-tokens`), sent as an `Authorization: Bearer` header. `localStorage`
is readable by any JavaScript on the page, so an XSS or a compromised npm
dependency could exfiltrate the long-lived refresh token — a serious exposure
for an app holding mental-health data.

The app is not a pure SPA: it also serves server-rendered pages (the marketing
site, the invite-redemption flow). Those already run a ring session — it backs
the HTML anti-forgery token. But the SPA and the server-rendered side could not
share an auth identity: TASK-007's invite redemption had to bounce a
freshly-created account to the login screen, because a server-rendered page had
no way to log a user into the SPA.

## Decision

Authenticate with a **server-side session** in an httpOnly cookie.

- One ring session, shared by the HTML routes and `/api` — an encrypted (AES)
  `cookie-store`, so there is no session table and no per-request DB read.
  Cookie attributes: `HttpOnly`, `SameSite=Lax`, `Secure` in production,
  absolute 14-day `Max-Age`.
- buddy's `backends/session` replaces the `jws` backend; `[:session :identity]`
  holds `{:sub user-id}` — the same shape handlers already read.
- `/api` gains ring's session-backed anti-forgery. Cookies are auto-sent, so
  the bearer-token CSRF immunity is spent; the SPA sends the token as an
  `X-CSRF-Token` header.
- The access/refresh JWT split, the `refresh_tokens` table, and the token
  cleanup job are deleted.

## Alternatives considered

- **JWTs moved into httpOnly cookies.** A smaller diff, but it leaves JWT
  cookies running alongside the ring session the HTML side already needs, and
  `/api` CSRF would need a second, separate scheme. The access/refresh split
  exists only to limit a *stolen* token's window — httpOnly removes the theft,
  so it removes the reason for the split. Rejected as accidental complexity for
  a single-origin, single-client app.
- **DB-backed session store.** Allows server-side revocation ("log out
  everywhere"). Deferred — see Consequences — to keep this migration's result
  simple; the store is an isolated later swap (TASK-023).

## Consequences

- **No server-side revocation, and only browser-enforced expiry.** With the
  cookie store there is no session record to delete: logout clears the cookie
  in the current browser, but a stolen cookie cannot be killed. The 14-day
  `Max-Age` bounds a cookie held by a *browser* — but the encrypted payload
  carries no expiry timestamp, so a raw cookie value replayed by a non-browser
  client is not time-bounded short of rotating `:session/key` (which logs
  everyone out). Genuine server-side expiry and revocation ("log out
  everywhere") require the DB-backed store — deferred to TASK-023.
- The invite redemption (TASK-007) now establishes the session directly and
  redirects a new member straight into `/app`, already signed in.
- `:session/key` (16 bytes) must be stable in production — rotating it
  invalidates every active session. It is set via `PARTS__SESSION__KEY`.
- Terminology: this **auth session** is distinct from the **Session** (a
  therapy session) in CONTEXT.md's glossary.
