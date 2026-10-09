# ADR 0019: Operator console behind an SSH tunnel, with no app login

## Status

Accepted — 2026-10-09.

## Context

The operator needs a web UI for fleet stats and for composing emails to Users
(Markdown with a preview), which the production REPL does poorly. Parts has no
admin role: "operator" means whoever can reach the prod nREPL, a 0600 unix
socket opened over SSH (runbook, "REPL access"). That nREPL can already do
everything — read every Map, send any email.

## Decision

The **Operator console** is a second http-kit listener in the same JVM, with its
own reitit router. Caddy never proxies it. In production it binds a **unix
socket, mode 0600**, exactly like the nREPL, so only the app user (and an
operator who grants themselves an ACL via sudo) can connect; a TCP loopback port
would be open to every local process. In dev it binds a TCP port on `127.0.0.1`.
The operator reaches it with `ssh -L <port>:/run/parts/console.sock`. **The SSH
tunnel is the auth boundary**: there is no app login and no `is_admin` flag.

Two guards stay, because a tunnel makes the console reachable from every page in
the operator's browser:

- **Host-header allowlist** (`localhost` or `127.0.0.1`, any port, since the
  operator picks the local port) — blocks DNS rebinding, which would let a
  hostile page read the console (User emails, stats).
- **Anti-forgery tokens on every POST** — blocks a hostile page from submitting a
  send-to-all form.

## Considered options

- **`/admin` on the public app, gated by an admin flag.** Rejected: puts an
  operator surface and a high-value account on the internet, for an app holding
  mental-health data.
- **Tunnel plus an in-app login.** Rejected: the person who can open the tunnel
  can open the nREPL, which outranks any console. A second credential protects
  against almost nothing and adds an account to steal.
- **A separate process.** Rejected: doubles deploy and config for no isolation
  gain; a separate router object already keeps console routes off the public port.

## Consequences

- The console has its own session cookie name. Cookies are scoped by host, not
  port, so in dev a shared name with the app on `localhost` would clobber the
  app login.
- Revisit if a second person needs console access without nREPL access: that is
  the first moment a per-person login means something.
