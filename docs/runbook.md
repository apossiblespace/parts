# Operator runbook

Day-to-day operations for the Parts production service. Deploying is covered in
the README — this is the other half: *something looks wrong, where do I look?*

## Where errors go

Parts logs through `mulog`. In production (`server.clj` → `start-log-publisher`)
the publisher is `:console-json` — one line of JSON per event on stdout. The
service runs as the systemd unit **`parts`**, so stdout is captured by
**journald**. There is no separate log file and no external error service:
journald is the whole log.

Unhandled exceptions are caught in one place — the Ring exception middleware in
`aps.parts.errors` — and logged under these event names:

- `unhandled-exception` — an uncaught 500
- `postgres-exception` — a database constraint violation
- `batch-failure` — a Map change-batch was rolled back

Every event also carries global context: `app-name`, `version`, `env`. A
`request` event is logged for each HTTP request (URI, method, user-id) — useful
for reconstructing what a user did just before an error.

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

To confirm errors actually reach you, emit one through the live pipeline via
the production REPL (see "Production REPL access" below):

```sh
# from your laptop — forward a local TCP port to the server's REPL socket
ssh -L 7888:/run/parts/nrepl.sock parts
# then, in another terminal, connect your nREPL client to localhost:7888
```

Evaluate:

```clojure
(require '[com.brunobonacci.mulog :as mulog])
(mulog/log :aps.parts.errors/unhandled-exception
           :error "TEST — observability check, please ignore")
```

Then confirm it appears within your expected response window:

```sh
journalctl -u parts --since "5 min ago" | grep "observability check"
```

This exercises the real publisher → journald path. Because
`unhandled-exception` is on the alert allowlist (see below), with SMTP
configured **this also sends you an alert email** — so the one snippet verifies
both the journald path *and* the email path at once. Re-run it whenever you want
to re-verify — after a deploy, or a host change.

## Verify journald keeps the log

journald can be configured as volatile (memory-only) — confirm it persists, so
errors survive a service restart:

```sh
# /var/log/journal/ must exist for persistent storage
ls -d /var/log/journal && echo persistent || echo "volatile — set Storage= in journald.conf"

# how much history is retained
journalctl -u parts --disk-usage
```

If storage is volatile, set `Storage=persistent` in
`/etc/systemd/journald.conf` and `systemctl restart systemd-journald`.

## Error alerts (email)

When SMTP is configured, the server pushes an email the first time an
alert-worthy error happens, so you don't have to be watching the log. This is
the operator-alert sink in `aps.parts.alerts` — a mulog publisher, deliberately
*not* a general mailer (see TASK-014 for real transactional email).

**What triggers an alert** — three event names, no others:
`unhandled-exception`, `batch-failure`, `postgres-exception`. Handled
validation 409s aside, anything that lands in those events emails you.

**Throttle** — a given error signature is suppressed for 15 minutes after it
fires once, so a crash-loop collapses to a single email instead of thousands.
Postgres errors group by SQL-state (so ten different duplicate-key hits are one
alert, not ten). Distinct errors each alert immediately. The window is
in-memory and resets on restart (a redeploy re-arms alerting).

**Subject line** is prefixed `[parts-alert]` (filterable in your mail client)
and carries the deployment domain — `[parts-alert][parts.ifs.tools]
unhandled-exception` — so a staging test error is never mistaken for a prod
incident.

**Configuration** — all via environment (nothing committed; the repo is
public). The `PARTS__SMTP__*` relay credentials are shared with transactional
mail (see below); alerting itself stays *off* until the recipient is also set:

```sh
PARTS__SMTP__HOST=smtp.tem.scw.cloud  # Scaleway TEM relay (ADR-0016)
PARTS__SMTP__PORT=587                 # use 587 (STARTTLS) on Hetzner — see note
PARTS__SMTP__USER=<scaleway-project-id>
PARTS__SMTP__PASSWORD=<scaleway-api-secret-key>
PARTS__ALERT__TO=<where alerts go>
PARTS__ALERT__FROM=<optional; defaults to the SMTP user>
```

> **Hetzner outbound-mail block.** Hetzner filters outbound SMTP egress: on the
> prod box, ports **25 and 465 time out**, but **587 is open**. So use
> `PARTS__SMTP__PORT=587` (STARTTLS). The code sets the matching transport flag
> from the port automatically (587 → `:tls`, 465 → `:ssl`; the rule lives
> beside each SMTP client, in `aps.parts.mail` and `aps.parts.alerts`) — you
> only set the port number. To re-check egress on a new host:
> ```sh
> for p in 25 465 587; do
>   timeout 5 bash -c "echo > /dev/tcp/smtp.tem.scw.cloud/$p" 2>/dev/null \
>     && echo "$p OPEN" || echo "$p BLOCKED"
> done
> ```
> If even 587 is blocked, ask Hetzner support to unblock outbound mail, or
> switch to an HTTPS mail path (port 443 is never filtered).

## Transactional email

`aps.parts.mail` sends user-facing mail (invites today; future self-serve
flows) over the same `PARTS__SMTP__*` relay — Scaleway TEM, with `ifs.tools`
as the verified sending domain (SPF/DKIM/DMARC; see ADR-0016). Two further
variables identify the sender:

```sh
PARTS__MAIL__FROM='Gosha <gosha@ifs.tools>'   # must be on the verified domain
PARTS__MAIL__REPLY_TO=<personal address>      # optional; invites carry it so replies reach a human
```

Sending fails loudly (`:config-error`) until `PARTS__MAIL__FROM` is set —
there is no silent fallback. Bounce/suppression handling is provider-side:
Scaleway blocklists plus the TEM console (deliberate, ADR-0016); check the
console if a recipient reports nothing arrived.

Invites from the production REPL go through this layer:

```clojure
(require '[aps.parts.ops :as ops])
(ops/send-invitation-email! (ops/generate-invitation! "jane@example.com"))
```

On boot the server logs `::alerting-disabled` if SMTP isn't fully configured —
grep for it to confirm whether alerting is live. A send that fails (Fastmail
down, bad creds) is swallowed and logged as `::alert-send-failed`; alerting can
never take the app down.

**Still deferred (TASK-030):** *spike* alerting — ">N errors in M minutes"
thresholds. That's a scale-time concern; at concierge scale a first-occurrence
email per distinct error is the right signal. Until then, a proactive
historical grep once or twice a day remains a sensible backstop.

## Billing (concierge)

Billing is **out of band**: you send each invoice by hand from the Stripe
dashboard, and once it clears you move the account forward with the
`aps.parts.billing` REPL helpers (`(billing-status!)` to see standing,
`(set-paid-through! email)` to extend it). The application itself never calls
Stripe — there is no integration.

**Keep client data out of Stripe.** Stripe prohibits health data and will not
sign a BAA, so a Stripe customer or invoice must carry **only the therapist's
own name and email** — never a client's name, a Map title, or any clinical
detail. Nothing in the app can leak it for you (there's no Stripe integration);
this is purely a discipline for what you type into the dashboard by hand. When
the self-serve integration lands (TASK-046), this becomes a code invariant
instead of a manual rule.

## Backups & retention

A daily systemd timer (`parts-backup.timer` → `/usr/local/bin/parts-backup`,
both written by `scripts/bootstrap-prod.sh`) takes an encrypted Postgres dump
to Scaleway object storage (`scaleway:parts-prod-backup`):

```
pg_dump --format=custom | age --recipients-file … | rclone rcat …/parts_prod-<ts>.dump.age
```

The job runs as the dedicated **`parts-backup`** system user, not the app
user: the Scaleway credentials live in `/home/parts-backup/.config/rclone/`
(home `0700`), unreadable by `parts` — an app compromise can't reach the
bucket at all. Its DB access is a read-only postgres role (`pg_read_all_data`,
peer-authenticated). Run any manual rclone command against the bucket as that
user: `sudo -u parts-backup rclone lsf scaleway:parts-prod-backup/`.

The age **private** key never lives on the server — only the public recipient
(`/etc/parts/backup-recipient.age`) does. The private identity stays on your
laptop (`~/.config/parts-backup/identity.txt`), so a server compromise cannot
decrypt the backups. Restore (on your laptop, for drills or recovery):

```sh
scripts/restore-from-backup.sh ~/Downloads/parts_prod-<ts>.dump.age parts_restore_test
```

**Append-only by design.** The backup credential on the box has `s3:ListBucket`
+ `s3:PutObject` only — **no Delete** (and no `GetObject`). A compromised server
can add backups but cannot wipe, overwrite, read, or encrypt them (ransomware /
tamper resistance). Keep it that way: never grant the box's key delete rights.

**The key cannot read, so the upload must never read.** Because a `HEAD` is
authorized as `GetObject`, any rclone operation that stats an object 403s.
The backup therefore spools the encrypted dump to a temp file and uploads it
with `rclone copyto --s3-no-check-bucket --s3-no-head --no-check-dest` — one
known-size `PutObject`, no reads. Do **not** use `rclone rcat`: streaming with
unknown length becomes a multipart upload whose metadata read-back fails, which
is what made backups report failure nightly from 2026-07-22 (the objects landed
and restored fine; only the exit code lied). Same reason `rclone touch` and a
bare `copyto` fail: both stat first.

**Failure alerting.** `parts-backup.service` (and `parts.service`) carry
`OnFailure=parts-alert@%n.service`, a templated unit that mails the failing
unit's last 40 journal lines via `/usr/local/bin/parts-alert` — which reuses
the app's SMTP settings from `/etc/parts.env`, so alerting is configured in
one place. Test it end to end with a unit that is guaranteed to fail:

```sh
systemd-run --unit=alert-selftest --property=OnFailure=parts-alert@%n.service /bin/false
# an email titled "[parts] unit FAILED on <host>: alert-selftest.service" should arrive
```

If nothing arrives, check `journalctl -u parts-alert@*` — with SMTP unset the
mailer exits 0 with "SMTP not configured", by the same rule as the app.

**Standing check — verify the key really can't delete.** That property lives
in the Scaleway console, not this repo, so re-verify at setup, after any key
rotation, and alongside the retention check. Read the bucket policy:
**Object Storage → parts-prod-backup → Bucket settings → Bucket policy**. The
app principal's statement must list exactly:

```json
"Action": [ "s3:ListBucket", "s3:PutObject" ]
```

No `s3:DeleteObject` (can't destroy), no `s3:GetObject` (can't read back —
this is also why the upload must not stat, see above). The separate
`user_id:` statement with `"Action": "*"` is the owner's own access and is
expected; that principal is you in the console, not the box.

Prefer reading the policy over probing with a write: `rclone deletefile`
needs a stat first, so a denial there proves nothing about delete rights.

**30-day retention (a published promise).** The Privacy Policy and DPA state
that erasure propagates through backups within 30 days. Because the box can't
delete, retention is enforced by a **Scaleway bucket lifecycle rule** that
expires objects 30 days after creation — set by the bucket *owner* (your
full-access account), not the box. Apply it once (adjust endpoint/region if they
differ):

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

(Or set the same rule in the Scaleway console: Object Storage → the bucket →
Lifecycle rules → expire current versions after 30 days.)

**Versioning must stay OFF** (confirmed at setup). Lifecycle expiration on a
*versioned* bucket only adds delete-markers and keeps noncurrent versions past
30 days — silently breaking the promise. Don't enable versioning, and don't add
a rule that retains noncurrent versions. Object-lock must also be off (it can
only be set at bucket creation; this bucket was created without it).

**Verify (standing check — run at setup and periodically):**

```sh
scripts/verify-backup-retention.sh          # scaleway:parts-prod-backup
```

It asserts no artifact is older than 30 days and that versioning isn't enabled.
Backup runs are logged under `journalctl -u parts-backup --since '1 day ago'`.

*Reading a near-boundary failure.* The check stamps each backup's age from its
filename (`date -u`, so it's timezone-proof — your laptop's zone and the box's
zone are irrelevant). Scaleway's lifecycle expiry, though, is day-boundary
aligned (creation rounded down to midnight UTC, +30 days) and swept
asynchronously, so the oldest backup normally lingers a few hours past its
30-day mark before Scaleway removes it. To avoid crying wolf during that lag, the
check fails only past **32 days** (`RETENTION_DAYS` + `GRACE_DAYS` in the
script). So a *one-off* failure naming a single artifact ~30–32 days old is the
normal sweep lag — re-run a day later and it should be gone, with the count
steady at ~30–31. A failure that **persists** for the same object across days,
or a steadily climbing object count, means the lifecycle rule isn't actually
firing — confirm it exists with `aws s3api get-bucket-lifecycle-configuration`
(above) and that versioning is OFF.

**Restore drill (do once now, then ~quarterly).** A backup you've never restored
is a hope, not a backup. From your laptop:

1. **Download** the most recent `parts_prod-*.dump.age` from the Scaleway console
   (Object Storage → `parts-prod-backup` → newest by the timestamp in its name →
   Download). The box's append-only key can't read objects, so fetch it from the
   console while logged in as the owner — no CLI needed.
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

The production host uses LUKS full-disk encryption (TASK-043). The root
filesystem is encrypted, so the box **cannot boot unattended**: after any reboot
it halts in the initramfs waiting for the LUKS passphrase, delivered over SSH
(dropbear). Nothing auto-unlocks — by design, the key never lives on the box.

### Unlock after a reboot (the box is down until you do this)

1. The box reboots and stops in initramfs, where dropbear is listening on SSH.
   From your laptop:
   ```sh
   ssh root@parts.ifs.tools     # the pre-boot dropbear, NOT the booted OS
   cryptroot-unlock             # paste the LUKS passphrase
   ```
   A host-key warning the first time is expected — the initramfs has its own SSH
   host key, distinct from the booted OS. (`ssh-keygen -R parts.ifs.tools` clears
   a stale entry if it blocks you.)
2. The session ends and the box finishes booting. After ~30s, SSH in normally and
   confirm the stack came back on its own — every service is systemd-enabled, so
   unlocking the disk is the *only* manual step:
   ```sh
   ssh gosha@parts.ifs.tools
   systemctl is-active parts parts-dev caddy    # all 'active'
   curl -sSI https://parts.ifs.tools/           # 200
   ```

### Recovery kit (keep all of this OFF the box)

- **LUKS passphrase** — in your password manager. Without it the box can never
  boot again. Full stop.
- **LUKS header backup** — `cryptsetup luksHeaderBackup /dev/sda3
  --header-backup-file <file>`, stored off-box. Guards against a corrupted header
  bricking the whole disk (data intact but permanently unreadable). It holds the
  key slots, so it is only as current as the passphrase: **if you ever rotate the
  passphrase, regenerate the header backup and securely destroy the old copy** —
  the stale one still unlocks with the old passphrase.
- Never leave a working copy of the header on the running box (`shred -u` it after
  transfer).

The encrypted device is `/dev/sda3` (find it with `blkid -t TYPE=crypto_LUKS`).

## Session key (`PARTS__SESSION__KEY`)

The auth-session cookie is AES-encrypted with this 16-byte key (ADR-0007). The
provisioning scripts generate it once, with real entropy
(`tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16` ≈ 95 bits — **not**
`openssl rand -hex 8`, which is 16 chars but only 64 bits).

**Rotating the live key logs every user out** — the cookie store carries no
session table, so every existing cookie becomes undecryptable the moment the key
changes. Treat a rotation as a forced, fleet-wide re-login: do it deliberately
(e.g. on suspected key compromise), not casually. Re-provisioning an existing box
leaves the key untouched precisely to avoid this.

## PDF fonts (`PARTS__RENDER__FONT_DIR`)

The PDF renderer requires Noto Sans CJK TC (Regular + Bold OTF) on disk and
**fails fast at boot** when the files are missing — deliberately, because the
alternative failure mode is silent: FOP renders any glyph missing from its
font as a literal `#`, in a client-facing hand-out (ADR-0008).

`bootstrap-prod.sh` installs the files into `/var/lib/parts/fonts`
(checksum-verified, pinned to the noto-cjk `Sans2.004` release — the same
bytes the dev flake pins) and ensures `PARTS__RENDER__FONT_DIR` is present in
every env file; `add-instance.sh` does the same for instance env files.
**Boxes provisioned before this step need one `bootstrap-prod.sh` re-run
before deploying a build that includes the renderer** — the script is
idempotent and will only add the fonts and the env line.

Do not swap in a different font build "because it's newer": dev, CI, and
prod measuring identical font bytes is what keeps label wrapping identical
across environments. A version bump means updating the flake derivation, the
script checksums, and re-running both.

## Migrating to a new box

Standing up a fresh server (e.g. the move to a full-disk-encrypted box) is two
halves: provision the host (`scripts/bootstrap-prod.sh`, then
`scripts/add-instance.sh` for staging — see the README) and move the data. The
provisioning lives in those scripts; this covers the data move and the
non-obvious traps a database dump leaves behind.

### Dump on the old box, restore on the new

A single-database custom-format dump, moved over SSH (relay through your laptop
if the boxes can't reach each other), restored into a freshly-created database:

```sh
# OLD box — stop the app so the dump is consistent, then dump
sudo systemctl stop parts
sudo -u postgres pg_dump -Fc parts_prod > /tmp/parts_prod.dump

# transfer (laptop relay; 'parts' is the new box's SSH alias)
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

`pg_dump` of one database carries that database's tables, data, and the *grants*
inside it — but not everything the running app needs. All three of these bit
this migration; check each before declaring success.

1. **Cluster-global roles don't travel.** Roles and role *memberships* live in
   the cluster, not the database, so the dump references `deletion_role` in its
   grants but can't recreate the role — and the restored migration-tracking
   table marks that migration already-applied, so migratus won't recreate it
   either. Recreate it by hand (canonical grants live in migration
   `20260511000002-deletion-role`), reading the exact privileges off the old box:
   ```sh
   # on the old box — the authoritative grant + membership state:
   sudo -u postgres psql parts_prod -c \
     "SELECT table_name, privilege_type FROM information_schema.role_table_grants
      WHERE grantee='deletion_role' ORDER BY 1,2;"
   sudo -u postgres psql parts_prod -c \
     "SELECT m.rolname FROM pg_auth_members am
      JOIN pg_roles r ON r.oid=am.roleid JOIN pg_roles m ON m.oid=am.member
      WHERE r.rolname='deletion_role';"     # who is a member (e.g. parts)?
   ```
   Then on the new box: `CREATE ROLE deletion_role NOLOGIN;`, replay those
   `GRANT … TO deletion_role` lines, and `GRANT deletion_role TO parts` if the
   old box had the membership. Confirm parity by re-running the same two queries
   on the new box.

2. **`public` schema ownership can deny the app role.** Since PostgreSQL 15 only
   the schema owner may create objects in `public`. A restore overwrites the new
   database's `public` ownership with the old box's, which can leave the
   connecting role unable to run later migrations — boot fails with
   `permission denied for schema public` on a `CREATE TABLE`. For **staging,
   that's rehearsal data — rebuild the database fresh** and let migrations build
   it on a correctly-owned schema:
   ```sh
   sudo systemctl stop parts-dev
   sudo -u postgres psql -c "DROP DATABASE parts_dev;"
   sudo -u postgres psql -c "CREATE DATABASE parts_dev OWNER parts_dev;"
   sudo systemctl restart parts-dev
   ```
   To keep the data instead: `ALTER SCHEMA public OWNER TO <role>; GRANT ALL ON
   SCHEMA public TO <role>; REASSIGN OWNED BY <old-owner> TO <role>;`.
   This trap bit production on 2026-07-15 (five pending migrations failed at
   boot). Since then a `bootstrap-prod.sh` / `add-instance.sh` re-run
   force-syncs DB + `public` schema ownership to the app role — re-running the
   provisioning script after any restore is the standard remedy.

3. **The role password must match the env file.** `CREATE ROLE` doesn't travel
   either, so the new box's role keeps whatever password the provisioning script
   set — which a re-run could have left stale. If boot fails with
   `password authentication failed for user "parts"`, sync the role to the env
   value (this is over TCP + password, exactly as the app connects):
   ```sh
   DBPW=$(sudo sed -n 's/^PARTS__DB__PASSWORD=//p' /etc/parts.env)
   printf "ALTER ROLE parts PASSWORD '%s';\n" "$DBPW" | sudo -u postgres psql
   PGPASSWORD="$DBPW" psql -h localhost -U parts -d parts_prod -c 'SELECT 1;'   # verify
   ```
   The current scripts force-sync this on provision; older runs may not have.

### Finish

Start the service and watch one clean boot — migrations connect and apply,
`application-startup` on the bound port, no exceptions:

```sh
sudo systemctl restart parts && journalctl -u parts -f
```

Confirm the data is all present (`\dt`, key row counts against the old box), log
in to smoke-test — then, only once verified, retire the old box.

## Erasure least-privilege (`deletion_role`)

Normal operation never hard-DELETEs from the temporal tables (`users`,
`maps`, `map_metadata`, `parts`, `relationships`) — only the erasure purge
does. That invariant is enforced in three layers:

1. **Provisioning** (`bootstrap-prod.sh` / `add-instance.sh`, as the
   postgres superuser): creates `deletion_role` (NOLOGIN) and grants the app
   role membership **`WITH INHERIT FALSE`** — an inheriting membership hands
   the app role every deletion_role privilege passively, silently undoing
   the revoke (found live on staging; a re-grant updates the option in
   place). Must pre-exist before first boot — the app role holds
   `NOCREATEROLE`.
2. **Migration `20260726000000`** (as the app role): grants `deletion_role`
   everything the purge touches and `REVOKE DELETE ... FROM CURRENT_USER` on
   the temporal tables.
3. **The purge** (`db/erasure.clj`): `SET LOCAL ROLE deletion_role` for the
   purge transaction only.

The revoke is a **speed bump, not a wall**: the app role owns the tables and
an owner can re-grant itself DELETE. It still stops every accidental or
injected DELETE in normal query paths (the threat it targets); ownership
separation was considered and deliberately not taken (migration comment has
the full rationale).

**Verify on a running box** (expect *permission denied*, then *DELETE 0*):

```sh
sudo -u postgres psql -d parts_prod -c "SET ROLE parts; DELETE FROM parts WHERE false;"
sudo -u postgres psql -d parts_prod -c "SET ROLE parts; SET ROLE deletion_role; DELETE FROM parts WHERE false;"
```

## Production REPL access

The prod app runs an nREPL on a **unix domain socket**,
`/run/parts/nrepl.sock` (`prod.edn :repl/socket`), permissioned `0600`,
owned `parts:parts`. nREPL has **no authentication** — a connected client
has arbitrary code execution as the app user, including its DB credentials
and every Map's clinical content. The socket gate means "may connect"
requires filesystem access as `parts`, not merely "runs on the box": a
loopback **TCP** REPL would be connectable by *any* local process (the
oauth2-proxy sidecar, a compromised dependency, an SSRF-to-localhost gadget).

**Access is sudo-gated, on purpose.** Root SSH is disabled, so the operator
opens the SSH forward as their own account — which cannot read a `0600`
socket owned by `parts`. Grant yourself a temporary ACL first:

```sh
# on the box — both lines need sudo, which is the point
sudo setfacl -m u:$USER:x  /run/parts            # traverse the 0750 dir
sudo setfacl -m u:$USER:rw /run/parts/nrepl.sock
```

Then from the laptop:

```sh
ssh -L 7888:/run/parts/nrepl.sock <admin>@parts-prod
# then: M-x cider-connect-clj → localhost 7888
#   (staging: /run/parts-dev, /run/parts-dev/nrepl.sock)
```

The grant dies with the socket when the service restarts — re-run it after
each deploy. Do **not** shortcut this by adding the admin account to the
`parts` group: that would make REPL access (and therefore the whole
database) reachable with a stolen SSH key alone, with no sudo password.
Keeping the password in front of it is what makes the gate worth having.

CIDER connects in a **degraded mode**: the production artifact deliberately
ships without `cider-nrepl`, so evaluation and `aps.parts.ops` work but
completion, the inspector, and the debugger do not. That is the trade for a
minimal in-process RCE surface — do not add the middleware back to prod.

Residual risk, deliberately accepted: code already running *as* `parts` (an
app RCE) can use the socket, but it can already do everything the REPL
offers. `PARTS__REPL__PORT` re-enables a loopback TCP REPL as an explicit
escape hatch — leave it unset. The production artifact ships plain nREPL
only (no cider middleware, no test runner; those are dev aliases).

## Rate limiting & the trusted client IP (`X-Real-IP`)

The per-IP rate limiter (`aps.parts.ratelimit`, on login / register / invite)
buckets clients by a **single proxy-set header, `X-Real-IP`** — never by
`X-Forwarded-For`. The invariant:

> **Whoever is the edge proxy sets `X-Real-IP` from its real peer and overwrites
> any client-supplied value. The app trusts exactly that header and nothing
> about the X-Forwarded-For chain.**

Why not X-Forwarded-For: it is client-appendable, and its length varies by
route — `/api/*` reaches the app one hop from Caddy, while `/invite` sits behind
an extra `oauth2-proxy` hop. No fixed position in that chain is reliably the
client, so trusting it let an attacker rotate the value to dodge the limiter
(the original TASK-088 bug). Caddy sets `X-Real-IP` via `header_up` in the
generated Caddyfiles (`bootstrap-prod.sh`, `add-instance.sh`).

**Verify on a live box** (do this after provisioning, and any time the proxy
chain changes). Both must show the *real* client IP, not `127.0.0.1`:

```sh
# 1-hop path (login/register) — Caddy → app
curl -s https://$DOMAIN/api/... ; journalctl -u parts -n1 | grep -o '"remote_addr[^,]*'
# 2-hop path (invite) — Caddy → oauth2-proxy → app; proves oauth2-proxy
# (OAUTH2_PROXY_REVERSE_PROXY=true) forwards X-Real-IP rather than clobbering it
curl -s https://$DOMAIN/invite/<token> ; journalctl -u parts -n1
```
If the 2-hop path shows `127.0.0.1`, invite-redemption rate limiting is coarse
(all clients share one bucket) — safe (over-throttling), but fix by configuring
oauth2-proxy to preserve/forward the client IP.

**If a CDN is ever added** it becomes the new edge: Caddy's peer becomes the CDN,
so `X-Real-IP {http.request.remote.host}` would key on the CDN's IP. Re-anchor by
having the CDN forward the client IP, validating the CDN as the trusted peer, and
pointing the app at the right header via `PARTS__RATELIMIT__CLIENT_IP_HEADER`.
