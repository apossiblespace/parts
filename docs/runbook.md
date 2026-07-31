# Operator runbook

Day-to-day operations for the Parts production service. Deploying is covered in
the README — this is the other half: *something looks wrong, where do I look?*

Throughout, `parts` is the SSH alias for the production box.

## Where errors go

Parts logs through `mulog`. In production the publisher is `:console-json`
(`server.clj` → `start-log-publisher`): one JSON line per event on stdout,
captured by journald via the systemd unit `parts`. There is no separate log
file and no external error service — journald is the whole log.

Unhandled exceptions are caught in one place (the Ring exception middleware in
`aps.parts.errors`) and logged as:

- `unhandled-exception` — an uncaught 500
- `postgres-exception` — a database constraint violation
- `batch-failure` — a Map change-batch was rolled back

Every event carries `app-name`, `version`, `env`. A `request` event is logged
per HTTP request (URI, method, user-id) — useful for reconstructing what a
user did just before an error.

## Live errors — tail the log

```sh
ssh parts
journalctl -u parts -f
```

Just the errors:

```sh
journalctl -u parts -f | grep -E 'unhandled-exception|postgres-exception|batch-failure'
```

Each match is a JSON line — pipe it through `jq` to read it:

```sh
journalctl -u parts -f -o cat | grep --line-buffered unhandled-exception | jq .
```

## Historical errors — search the log

```sh
# last hour
journalctl -u parts --since "1 hour ago" | grep unhandled-exception

# a specific window
journalctl -u parts --since "2026-05-22 09:00" --until "2026-05-22 12:00" | grep unhandled-exception

# count today's errors
journalctl -u parts --since today -o cat | grep -c unhandled-exception
```

## Raise a test error — verify the pipeline

Emits a real event through the publisher → journald path. Because
`unhandled-exception` is on the alert allowlist, with SMTP configured this
also sends an alert email — one snippet verifies both paths. Re-run after a
deploy or a host change. (Space repeat runs 15+ minutes apart or vary the
message — the alert throttle suppresses identical signatures.)

1. Grant yourself socket access (see "Production REPL access" below), then
   forward a local port to the REPL socket:
   ```sh
   ssh -L 7888:/run/parts/nrepl.sock parts
   # in another terminal, connect your nREPL client to localhost:7888
   ```
2. Evaluate:
   ```clojure
   (require '[com.brunobonacci.mulog :as mulog])
   (mulog/log :aps.parts.errors/unhandled-exception
              :error "TEST — observability check, please ignore")
   ```
3. Confirm it landed:
   ```sh
   journalctl -u parts --since "5 min ago" | grep "observability check"
   ```

## Verify journald keeps the log

journald can be configured as volatile (memory-only) — confirm it persists, so
errors survive a restart:

```sh
# /var/log/journal/ must exist for persistent storage
ls -d /var/log/journal && echo persistent || echo "volatile — set Storage= in journald.conf"

# how much history is retained
journalctl -u parts --disk-usage
```

If volatile, set `Storage=persistent` in `/etc/systemd/journald.conf` and
`systemctl restart systemd-journald`.

## Error alerts (email)

With SMTP configured, the server emails you the first time an alert-worthy
error happens. This is the operator-alert sink in `aps.parts.alerts` — a mulog
publisher, deliberately not a general mailer (user-facing mail is
`aps.parts.mail`, next section).

**Triggers** — exactly three event names: `unhandled-exception`,
`batch-failure`, `postgres-exception`. Expected validation failures (409s)
don't alert.

**Throttle** — an error signature is suppressed for 15 minutes after it fires,
so a crash-loop collapses to one email. Postgres errors group by SQL-state;
distinct errors alert immediately. The window is in-memory — a restart
re-arms it.

**Subject** — `[parts-alert][<domain>] <event>`; the domain distinguishes a
staging test from a prod incident. Note that unit-failure mails ("Failure
alerting", below) use the prefix `[parts]` instead — a mail filter on
`[parts-alert]` alone misses them.

**Is it on?** — on boot the server logs `::alerting-disabled` when SMTP isn't
fully configured; grep the journal for it. A failed send (relay down, bad
creds) is swallowed and logged as `::alert-send-failed` — alerting can never
take the app down.

**Configuration** — all via environment (nothing committed; the repo is
public). The `PARTS__SMTP__*` relay credentials are shared with transactional
mail; alerting stays *off* until the recipient is also set:

```sh
PARTS__SMTP__HOST=smtp.tem.scw.cloud  # Scaleway TEM relay (ADR-0016)
PARTS__SMTP__PORT=587                 # use 587 (STARTTLS) on Hetzner — see note
PARTS__SMTP__USER=<scaleway-project-id>
PARTS__SMTP__PASSWORD=<scaleway-api-secret-key>
PARTS__ALERT__TO=<where alerts go>
PARTS__ALERT__FROM=<optional; defaults to PARTS__MAIL__FROM, then the SMTP user>
```

> **Hetzner outbound-mail block.** Hetzner filters outbound SMTP: on the prod
> box ports **25 and 465 time out**, but **587 is open** — so use
> `PARTS__SMTP__PORT=587`. The code sets the transport flag from the port
> (587 → `:tls`, 465 → `:ssl`; the rule lives beside each SMTP client, in
> `aps.parts.mail` and `aps.parts.alerts`). To re-check egress on a new host:
> ```sh
> for p in 25 465 587; do
>   timeout 5 bash -c "echo > /dev/tcp/smtp.tem.scw.cloud/$p" 2>/dev/null \
>     && echo "$p OPEN" || echo "$p BLOCKED"
> done
> ```
> If even 587 is blocked, ask Hetzner support to unblock outbound mail, or
> switch to an HTTPS mail path (port 443 is never filtered).

**Deferred (TASK-030):** spike alerting — ">N errors in M minutes" thresholds.
Not needed at current scale; a first-occurrence email per distinct error, plus
an occasional historical grep, is the right signal.

## Transactional email

`aps.parts.mail` sends user-facing mail (invites today; future self-serve
flows) over the same `PARTS__SMTP__*` relay — Scaleway TEM, with `ifs.tools`
as the verified sending domain (SPF/DKIM/DMARC; ADR-0016). Two further
variables identify the sender:

```sh
PARTS__MAIL__FROM='Gosha <gosha@ifs.tools>'   # must be on the verified domain
PARTS__MAIL__REPLY_TO=<personal address>      # optional; invites carry it so replies reach a human
```

Sending fails loudly (`:config-error`) until `PARTS__MAIL__FROM` is set —
there is no silent fallback. Bounce/suppression handling is provider-side
(deliberate, ADR-0016) — check the TEM console if a recipient reports nothing
arrived.

Invites from the production REPL:

```clojure
(require '[aps.parts.ops :as ops])
(ops/send-invitation-email! (ops/generate-invitation! "jane@example.com"))
```

## Billing

Billing is self-serve (TASK-046), with one source of truth —
`users.paid_through_date`, which only ever moves forward. The app creates
Checkout/Portal sessions and consumes webhooks once all four
`PARTS__STRIPE__*` variables are set; unset, billing is off. The
restricted key needs four scopes: **Checkout Sessions: Write, Billing
Portal: Write, Customers: Write, and Subscriptions: Read**. Customers:
Write is what lets erasure delete a linked Customer; Subscriptions: Read
is what lets the checkout webhook fetch the paid period — missing either
surfaces as permission errors (failed deletions, or every completed
checkout 500ing and retrying). The webhook keeps `paid_through_date` and
`stripe_subscription_status` current.
The production webhook endpoint must be pinned to the latest Stripe API
version (the account default is 2018-era) and subscribed to
`checkout.session.completed`, `invoice.paid`, and the three
`customer.subscription.*` events.

**Operator adjustments** — from the production REPL: `(billing-status!)`
for standing, `(set-paid-through! email "2027-05-22")` for a goodwill
extension or correction, `(clear-paid-through! email)` to reset. The
setter never moves a date backwards; a deliberate claw-back (refund,
abuse response) is clear-then-set. Caveat: a Stripe `invoice.paid`
redelivery within ~3 days of the original event re-extends a clawed-back
date, so re-check after the retry window. The concierge hand-invoicing
lane is retired (2026-07-31): a paid invoice matching no linked account
now alerts (`invoice-unmatched`) instead of being quietly ignored, so
don't send ad-hoc invoices from this Stripe account.

**Keep client data out of Stripe.** Stripe prohibits health data and will
not sign a BAA, so a Stripe customer or invoice must carry **only the
therapist's own name and email** — never a client's name, a Map title, or
any clinical detail. For dashboard work this is discipline; in code it is
an invariant: the checkout payload is a closed allowlist pinned by
`aps.parts.stripe-test`.

**Erasure releases the Stripe link (TASK-108).** Account deletion — the
immediate path and the 30-day purge job alike — deletes the linked Stripe
Customer *before* the DB purge. Stripe immediately cancels the customer's
subscriptions (no charge outlives an account) and retains past invoices as
the financial records it must keep; removing the customer's identity from
our Stripe account is the extent of the erasure promise there. If Stripe is
unreachable, the purge is postponed to the next hourly run with the link
still on record — nothing half-completes. If an account is linked but the
host has no Stripe config, the purge proceeds and logs
`stripe-link-remains`: an operator work item to delete that customer from
the dashboard by hand.

## Backups & retention

A daily timer (`parts-backup.timer` → `/usr/local/bin/parts-backup`, both
written by `scripts/bootstrap-prod.sh`) ships an encrypted Postgres dump to
Scaleway object storage (`scaleway:parts-prod-backup`):

```
pg_dump --format=custom | age --recipients-file … > <spool>   # then:
rclone copyto --s3-no-check-bucket --s3-no-head --no-check-dest <spool> …/parts_prod-<ts>.dump.age
```

- Runs as the dedicated **`parts-backup`** system user. The Scaleway
  credentials (`/home/parts-backup/.config/rclone/`, home `0700`) are
  unreadable by the app user, so an app compromise can't reach the bucket.
  DB access is a read-only role (`pg_read_all_data`, peer-authenticated).
- Manual rclone commands run as that user:
  `sudo -u parts-backup rclone lsf scaleway:parts-prod-backup/`
- The age **private** key never lives on the server — only the public
  recipient (`/etc/parts/backup-recipient.age`) does. The private identity
  stays on your laptop (`~/.config/parts-backup/identity.txt`), so a server
  compromise cannot decrypt the backups.

**Append-only by design.** The box's key has `s3:ListBucket` + `s3:PutObject`
only — no Delete, no GetObject. A compromised server can add backups but
cannot wipe, overwrite, read, or encrypt them. Never grant the box's key
delete rights.

**The key cannot read, so the upload must never read.** A `HEAD` is authorized
as `GetObject`, so any rclone operation that stats an object 403s. Hence the
spool + `copyto --s3-no-check-bucket --s3-no-head --no-check-dest` above: one
known-size `PutObject`, no reads. Don't switch to `rclone rcat` (unknown
length → multipart upload → metadata read-back 403s; this made backups report
failure nightly until fixed on 2026-07-22), `rclone touch`, or a bare
`copyto` — all of those stat first.

**Failure alerting.** `parts-backup.service` (and `parts.service`) carry
`OnFailure=parts-alert@%n.service`, a templated unit that mails the failing
unit's last 40 journal lines via `/usr/local/bin/parts-alert` — which reuses
the app's SMTP settings from `/etc/parts.env`, so alerting is configured in
one place. Test end to end with a unit guaranteed to fail:

```sh
systemd-run --unit=alert-selftest --property=OnFailure=parts-alert@%n.service /bin/false
# an email titled "[parts] unit FAILED on <host>: alert-selftest.service" should arrive
```

If nothing arrives, check `journalctl -u parts-alert@*` — with SMTP unset the
mailer exits 0 with "SMTP not configured", by the same rule as the app.

**Standing check — verify the key really can't delete.** This lives in the
Scaleway console, not the repo — re-verify at setup, after any key rotation,
and alongside the retention check. Read the bucket policy (**Object Storage →
parts-prod-backup → Bucket settings → Bucket policy**); the app principal's
statement must list exactly:

```json
"Action": [ "s3:ListBucket", "s3:PutObject" ]
```

No `s3:DeleteObject`, no `s3:GetObject`. The separate `user_id:` statement
with `"Action": "*"` is your own console access and is expected. Read the
policy rather than probing with a write: `rclone deletefile` stats first, so a
denial there proves nothing about delete rights.

**30-day retention (a published promise).** The Privacy Policy and DPA state
that erasure propagates through backups within 30 days. Because the box can't
delete, retention is enforced by a Scaleway bucket **lifecycle rule** expiring
objects 30 days after creation — set by the bucket owner (your full-access
account), not the box. Apply once (adjust endpoint/region if they differ):

```sh
cat > /tmp/lifecycle.json <<'JSON'
{ "Rules": [ {
    "ID": "expire-backups-30d",
    "Status": "Enabled",
    "Filter": { "Prefix": "" },
    "Expiration": { "Days": 30 }
} ] }
JSON
aws s3api put-bucket-lifecycle-configuration \
  --bucket parts-prod-backup --endpoint-url https://s3.fr-par.scw.cloud \
  --lifecycle-configuration file:///tmp/lifecycle.json
# confirm:
aws s3api get-bucket-lifecycle-configuration \
  --bucket parts-prod-backup --endpoint-url https://s3.fr-par.scw.cloud
```

(Or in the console: Object Storage → the bucket → Lifecycle rules → expire
current versions after 30 days.)

**Versioning must stay OFF** (confirmed at setup). Lifecycle expiration on a
versioned bucket only adds delete-markers and keeps noncurrent versions past
30 days — silently breaking the promise. Don't enable versioning, don't add a
rule retaining noncurrent versions. Object-lock must also be off (settable
only at bucket creation; this bucket was created without it).

**Verify (run at setup and periodically):**

```sh
scripts/verify-backup-retention.sh          # scaleway:parts-prod-backup
```

Asserts no artifact is older than 30 days (32 with grace, below) and that
versioning is off. Backup runs are logged under
`journalctl -u parts-backup --since '1 day ago'`.

Reading a near-boundary failure — ages come from filenames in UTC, but
Scaleway's sweep is day-aligned and asynchronous, so the check only fails past
**32 days** (`RETENTION_DAYS` + `GRACE_DAYS` in the script):

- A one-off failure naming a single artifact ~30–32 days old is normal sweep
  lag — re-run a day later; it should be gone, count steady at ~30–31.
- The same object failing across days, or a steadily climbing count, means
  the lifecycle rule isn't firing — confirm it exists
  (`get-bucket-lifecycle-configuration`, above) and that versioning is off.

**Restore drill (do once now, then ~quarterly).** A backup you've never
restored is a hope, not a backup. From your laptop:

1. **Download** the most recent `parts_prod-*.dump.age` from the Scaleway
   console (Object Storage → `parts-prod-backup` → newest by the timestamp in
   its name → Download). The box's key can't read objects, so fetch it from
   the console as the owner — no CLI needed.
2. **Decrypt + restore** into a throwaway DB (uses your laptop's age key at
   `~/.config/parts-backup/identity.txt`):
   ```sh
   scripts/restore-from-backup.sh ~/Downloads/<file>.dump.age parts_restore_test
   ```
3. **Sanity-check**:
   ```sh
   psql parts_restore_test -c '\dt'                          # tables present
   psql parts_restore_test -c 'SELECT count(*) FROM users;'  # plausible count
   ```
4. **Clean up**: `dropdb parts_restore_test`

If step 2 or 3 fails, the backups are not real backups — fix before launch.

## Disk encryption (FDE) & unlocking after a reboot

The production host uses LUKS full-disk encryption (TASK-043). The box
**cannot boot unattended**: after any reboot it halts in the initramfs waiting
for the LUKS passphrase, delivered over SSH (dropbear). Nothing auto-unlocks —
by design, the key never lives on the box.

### Unlock after a reboot (the box is down until you do this)

1. From your laptop (the raw hostname, not the alias — this is the pre-boot
   dropbear, not the booted OS):
   ```sh
   ssh root@parts.ifs.tools
   cryptroot-unlock             # paste the LUKS passphrase
   ```
   A host-key warning the first time is expected — the initramfs has its own
   SSH host key. (`ssh-keygen -R parts.ifs.tools` clears a stale entry.)
2. The session ends and the box finishes booting. After ~30s, SSH in normally
   and confirm everything came back — all services are systemd-enabled, so
   unlocking is the only manual step:
   ```sh
   ssh parts
   systemctl is-active postgresql parts parts-dev parts-dev-oauth2-proxy caddy   # all 'active'
   curl -sSI https://parts.ifs.tools/           # 200
   ```

### Recovery kit (keep all of this OFF the box)

- **LUKS passphrase** — in your password manager. Without it the box can
  never boot again. Full stop.
- **LUKS header backup** — `cryptsetup luksHeaderBackup /dev/sda3
  --header-backup-file <file>`, stored off-box. Guards against a corrupted
  header bricking the disk (data intact but unreadable). It holds the key
  slots, so it's only as current as the passphrase: **after rotating the
  passphrase, regenerate the header backup and securely destroy the old
  copy** — the stale one still unlocks with the old passphrase.
- Never leave a copy of the header on the running box (`shred -u` after
  transfer).

The encrypted device is `/dev/sda3` (find it with `blkid -t TYPE=crypto_LUKS`).

## Session key (`PARTS__SESSION__KEY`)

The auth-session cookie is AES-encrypted with this 16-byte key (ADR-0007). The
provisioning scripts generate it once, with real entropy
(`head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 16`
≈ 95 bits — **not** `openssl rand -hex 8`, which is 16 chars but only 64
bits). Keep that spelling: the input is bounded because an unbounded
`tr </dev/urandom | head` dies of SIGPIPE under a script's `pipefail`.

**Rotating the live key logs every user out** — there is no session table, so
every existing cookie becomes undecryptable the moment the key changes. Rotate
deliberately (e.g. on suspected compromise), never casually. Re-provisioning
an existing box leaves the key untouched precisely to avoid this.

## PDF fonts (`PARTS__RENDER__FONT_DIR`)

The PDF renderer requires Noto Sans CJK TC (Regular + Bold OTF) on disk and
**fails fast at boot** when they're missing — deliberately, because the
alternative failure is silent: FOP renders missing glyphs as a literal `#` in
a client-facing hand-out (ADR-0008).

`bootstrap-prod.sh` installs the files into `/var/lib/parts/fonts`
(checksum-verified, pinned to the noto-cjk `Sans2.004` release — the same
bytes the dev flake pins) and ensures `PARTS__RENDER__FONT_DIR` is in every
env file; `add-instance.sh` does the same for instances. **Boxes provisioned
before this step need one `bootstrap-prod.sh` re-run before deploying a build
with the renderer** — idempotent; it only adds the fonts and the env line.

Don't swap in a newer font build: dev, CI, and prod measuring identical font
bytes is what keeps label wrapping identical across environments. A version
bump means updating the flake derivation, the script checksums, and re-running
both.

## Migrating to a new box

Two halves: provision the host (`scripts/bootstrap-prod.sh`, then
`scripts/add-instance.sh` for staging — see the README) and move the data.
This section covers the data move and the traps a database dump leaves behind.

### Dump on the old box, restore on the new

A single-database custom-format dump, moved over SSH (relay through your
laptop if the boxes can't reach each other):

```sh
# OLD box — stop the app so the dump is consistent, then dump
sudo systemctl stop parts
sudo -u postgres pg_dump -Fc parts_prod > /tmp/parts_prod.dump

# transfer (laptop relay; 'parts' is the NEW box here)
scp OLDBOX:/tmp/parts_prod.dump /tmp/ && scp /tmp/parts_prod.dump parts:/tmp/

# NEW box — recreate empty, restore as postgres (replays owner + grants faithfully)
sudo systemctl stop parts
sudo -u postgres psql -c "DROP DATABASE IF EXISTS parts_prod;"
sudo -u postgres psql -c "CREATE DATABASE parts_prod OWNER parts;"
sudo -u postgres pg_restore -d parts_prod /tmp/parts_prod.dump
```

Then **shred the plaintext dump** everywhere it landed — `shred -u` on the old
(unencrypted) box especially, `rm` on the new encrypted one.

### Three traps a single-DB dump leaves behind

`pg_dump` of one database carries its tables, data, and internal grants — but
not everything the running app needs. All three of these bit this migration;
check each before declaring success.

1. **Cluster-global roles don't travel.** Roles and memberships live in the
   cluster, not the database: the dump references `deletion_role` in its
   grants but can't recreate the role — and the restored migration-tracking
   table marks the deletion-role migrations already-applied, so migratus won't
   recreate it either. Recreate by hand (canonical grants live in the two
   `*deletion-role*` migrations, `20260511000002` and `20260726000000`),
   reading the authoritative state off the old box:
   ```sh
   # on the old box — grants and membership:
   sudo -u postgres psql parts_prod -c \
     "SELECT table_name, privilege_type FROM information_schema.role_table_grants
      WHERE grantee='deletion_role' ORDER BY 1,2;"
   sudo -u postgres psql parts_prod -c \
     "SELECT m.rolname FROM pg_auth_members am
      JOIN pg_roles r ON r.oid=am.roleid JOIN pg_roles m ON m.oid=am.member
      WHERE r.rolname='deletion_role';"     # who is a member (e.g. parts)?
   ```
   On the new box: `CREATE ROLE deletion_role NOLOGIN;`, replay the
   `GRANT … TO deletion_role` lines, and `GRANT deletion_role TO parts` if the
   old box had the membership. Confirm parity by re-running both queries on
   the new box.

2. **`public` schema ownership can deny the app role.** Since PostgreSQL 15
   only the schema owner may create in `public`; a restore overwrites the new
   database's `public` ownership with the old box's, and boot then fails with
   `permission denied for schema public` (this bit production 2026-07-15). A
   `bootstrap-prod.sh` / `add-instance.sh` re-run force-syncs DB and schema
   ownership to the app role — **re-running the provisioning script after any
   restore is the standard remedy.** For staging, the data is rehearsal —
   simplest is a fresh database and let migrations rebuild it:
   ```sh
   sudo systemctl stop parts-dev
   sudo -u postgres psql -c "DROP DATABASE parts_dev;"
   sudo -u postgres psql -c "CREATE DATABASE parts_dev OWNER parts_dev;"
   sudo systemctl restart parts-dev
   ```
   To keep the data instead: `ALTER SCHEMA public OWNER TO <role>; GRANT ALL
   ON SCHEMA public TO <role>; REASSIGN OWNED BY <old-owner> TO <role>;`.

3. **The role password must match the env file.** `CREATE ROLE` doesn't travel
   either. If boot fails with `password authentication failed for user
   "parts"`, sync the role to the env value (over TCP + password, exactly as
   the app connects):
   ```sh
   DBPW=$(sudo sed -n 's/^PARTS__DB__PASSWORD=//p' /etc/parts.env)
   printf "ALTER ROLE parts PASSWORD '%s';\n" "$DBPW" | sudo -u postgres psql
   PGPASSWORD="$DBPW" psql -h localhost -U parts -d parts_prod -c 'SELECT 1;'   # verify
   ```
   Current scripts force-sync this on provision; older runs may not have.

### Finish

Start the service and watch one clean boot — migrations apply,
`application-startup` on the bound port, no exceptions:

```sh
sudo systemctl restart parts && journalctl -u parts -f
```

Confirm the data is present (`\dt`, key row counts against the old box), log
in to smoke-test — then, only once verified, retire the old box.

## Erasure least-privilege (`deletion_role`)

Normal operation never hard-DELETEs from the temporal tables (`users`, `maps`,
`map_metadata`, `parts`, `relationships`) — only the erasure purge does.
Enforced in three layers:

1. **Provisioning** (`bootstrap-prod.sh` / `add-instance.sh`, as the postgres
   superuser): creates `deletion_role` (NOLOGIN) and grants the app role
   membership **`WITH INHERIT FALSE`** — an inheriting membership would hand
   the app role every deletion_role privilege passively, silently undoing the
   revoke (found live on staging; a re-grant updates the option in place).
   Must pre-exist before first boot — the app role holds `NOCREATEROLE`.
2. **Migration `20260726000000`** (as the app role): grants `deletion_role`
   everything the purge touches and `REVOKE DELETE ... FROM CURRENT_USER` on
   the temporal tables.
3. **The purge** (`db/erasure.clj`): `SET LOCAL ROLE deletion_role` for the
   purge transaction only.

The revoke is a **speed bump, not a wall**: the app role owns the tables and
an owner can re-grant itself DELETE. It still stops every accidental or
injected DELETE in normal query paths — the threat it targets. Ownership
separation was considered and deliberately not taken (the migration comment
has the rationale).

**Verify on a running box** (expect *permission denied*, then *DELETE 0*):

```sh
sudo -u postgres psql -d parts_prod -c "SET ROLE parts; DELETE FROM parts WHERE false;"
sudo -u postgres psql -d parts_prod -c "SET ROLE parts; SET ROLE deletion_role; DELETE FROM parts WHERE false;"
```

## Production REPL access

```sh
# on the box — grant yourself temporary access to the socket
# (both lines need sudo, which is the point)
sudo setfacl -m u:$USER:x  /run/parts            # traverse the 0750 dir
sudo setfacl -m u:$USER:rw /run/parts/nrepl.sock

# from the laptop
ssh -L 7888:/run/parts/nrepl.sock parts
# then: M-x cider-connect-clj → localhost 7888
#   (staging: /run/parts-dev/nrepl.sock)
```

The grant dies with the socket when the service restarts — re-run it after
each deploy.

Why it's shaped this way: nREPL has **no authentication** — a connected client
has arbitrary code execution as the app user, including its DB credentials and
every Map's clinical content. The unix socket (`prod.edn :repl/socket`,
`0600`, owned `parts:parts`) means connecting requires filesystem access *as
`parts`*, not merely "runs on the box" — a loopback TCP REPL would be
connectable by any local process (the oauth2-proxy sidecar, a compromised
dependency, an SSRF-to-localhost gadget). `PARTS__REPL__PORT` re-enables
loopback TCP as an explicit escape hatch — leave it unset.

Do **not** shortcut the ACL by adding the admin account to the `parts` group:
that turns a stolen SSH key into passwordless code execution as the app user.
The sudo password in front is what makes the gate worth having.

CIDER connects in a **degraded mode**: the production artifact deliberately
ships plain nREPL without `cider-nrepl`, so evaluation and `aps.parts.ops`
work but completion, the inspector, and the debugger do not. That is the trade
for a minimal in-process RCE surface — do not add the middleware back to prod.

Residual risk, deliberately accepted: code already running *as* `parts` (an
app RCE) can use the socket — but it can already do everything the REPL
offers.

## Rate limiting & the trusted client IP (`X-Real-IP`)

The per-IP rate limiter (`aps.parts.ratelimit`, on login / register / invite)
buckets clients by a **single proxy-set header, `X-Real-IP`** — never by
`X-Forwarded-For`. The invariant:

> **Whoever is the edge proxy sets `X-Real-IP` from its real peer and
> overwrites any client-supplied value. The app trusts exactly that header and
> nothing about the X-Forwarded-For chain.**

Why not X-Forwarded-For: it is client-appendable, and its length varies by
route — `/api/*` reaches the app one hop from Caddy, while `/invite` sits
behind an extra oauth2-proxy hop. No fixed position in that chain is reliably
the client, so trusting it let an attacker rotate the value to dodge the
limiter (the original TASK-088 bug). Caddy sets `X-Real-IP` via `header_up` in
the generated Caddyfiles (`bootstrap-prod.sh`, `add-instance.sh`).

**Verify on a live box** (after provisioning, and any time the proxy chain
changes). Both must show the *real* client IP, not `127.0.0.1`:

```sh
# 1-hop path (login/register) — Caddy → app
curl -s https://$DOMAIN/api/... ; journalctl -u parts -n1 | grep -o '"remote_addr[^,]*'
# 2-hop path (invite) — Caddy → oauth2-proxy → app; proves oauth2-proxy
# (OAUTH2_PROXY_REVERSE_PROXY=true) forwards X-Real-IP rather than clobbering it
curl -s https://$DOMAIN/invite/<token> ; journalctl -u parts -n1
```

If the 2-hop path shows `127.0.0.1`, invite rate limiting is coarse (all
clients share one bucket) — safe (over-throttling), but fix by configuring
oauth2-proxy to forward the client IP.

**If a CDN is ever added** it becomes the new edge: Caddy's peer becomes the
CDN, so `X-Real-IP {http.request.remote.host}` would key on the CDN's IP.
Re-anchor by having the CDN forward the client IP, validating the CDN as the
trusted peer, and pointing the app at the right header via
`PARTS__RATELIMIT__CLIENT_IP_HEADER`.
