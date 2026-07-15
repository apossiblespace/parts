#!/usr/bin/env bash
set -euo pipefail

# Override the admin account name per-host: ADMIN_USER=gosha ./bootstrap-prod.sh
# (a re-run that forgets the override would otherwise create a second admin).
ADMIN_USER="${ADMIN_USER:-admin}"
APP_USER=parts
APP_NAME=parts
APP_DIR=/opt/$APP_NAME
# Set per-host: DOMAIN=parts.ifs.tools ./bootstrap-prod.sh. Left as the
# placeholder, the prod Caddy site matches no real traffic and gets no cert.
DOMAIN="${DOMAIN:-parts.example.com}"

# 1. basic packages
apt-get update
apt-get install -y \
    curl git ufw lnav \
    openjdk-21-jre-headless \
    postgresql-16 \
    caddy

# 2. users
# Admin user — a named account for SSH login + privilege escalation via sudo.
# Logging in as root over SSH is discouraged; the hardening banner printed at
# the end of this script covers the follow-up steps to disable root login.
id -u "$ADMIN_USER" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$ADMIN_USER"

# Grant sudo by adding the user to the `sudo` group — Ubuntu's default
# /etc/sudoers already grants that group full access, and sudo ships with the
# base system (no new dependency). Escalation requires the admin user to have
# a password set (see the hardening banner).
usermod -aG sudo "$ADMIN_USER"

# Seed the admin user's SSH key from root's, so you can log in as $ADMIN_USER
# immediately. Guarded: re-running bootstrap won't clobber keys you add later.
if [[ -f /root/.ssh/authorized_keys && ! -f /home/$ADMIN_USER/.ssh/authorized_keys ]]; then
    mkdir -p /home/$ADMIN_USER/.ssh
    cp /root/.ssh/authorized_keys /home/$ADMIN_USER/.ssh/authorized_keys
    chmod 700 /home/$ADMIN_USER/.ssh
    chmod 600 /home/$ADMIN_USER/.ssh/authorized_keys
    chown -R "$ADMIN_USER:$ADMIN_USER" /home/$ADMIN_USER/.ssh
elif [[ ! -f /root/.ssh/authorized_keys ]]; then
    echo "⚠️  /root/.ssh/authorized_keys not found — add a key to /home/$ADMIN_USER/.ssh/authorized_keys manually"
fi

# App user — a system account with no login shell. systemd runs the service;
# nothing ever needs to interactively be this user.
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"

mkdir -p "$APP_DIR/releases"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

# Legal-doc content dir — the operator pushes their Privacy Policy / ToS / DPA
# (<slug>.md + optional <slug>.pdf) here out-of-band; the app serves them at
# /privacy /terms /dpa (see :legal/content-dir in resources/parts/prod.edn).
# Owned by the admin user (who writes via the deploy), readable by the app user.
# Empty until content is pushed — until then the app serves the bundled examples.
mkdir -p /var/lib/parts/legal
chown "$ADMIN_USER:$APP_USER" /var/lib/parts/legal
chmod 755 /var/lib/parts/legal

# PDF document fonts — the renderer requires Noto Sans CJK TC (see
# ADR-0008): FOP renders glyphs missing from its font as a literal `#`,
# so the app fails fast at boot when these files are absent. Pinned to
# the same upstream release (and byte-identical files) as the dev
# flake's derivation, so every environment measures and embeds the same
# glyphs. Idempotent: files that already verify are left alone.
FONT_DIR=/var/lib/parts/fonts
FONT_BASE_URL=https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/OTF/TraditionalChinese
install_font() {
    local file=$1 sha=$2
    if ! echo "$sha  $FONT_DIR/$file" | sha256sum -c - >/dev/null 2>&1; then
        curl -fsSL -o "$FONT_DIR/$file" "$FONT_BASE_URL/$file"
        echo "$sha  $FONT_DIR/$file" | sha256sum -c - >/dev/null
        echo "✓ Installed $file"
    fi
}
mkdir -p "$FONT_DIR"
install_font NotoSansCJKtc-Regular.otf dce08bd4fd91aa8aa76ed8fea4b694c2dfb8550f67871e326843212ddbeb88b4
install_font NotoSansCJKtc-Bold.otf 3ee160e5015106e3ec1a394301df54fa9bbbf8a251519984aec5c0abc50840c0
chmod 755 "$FONT_DIR"
chmod 644 "$FONT_DIR"/*.otf

# 3. postgres + secrets + env file — generated once. The env file's existence
# is the "already provisioned" signal, so a re-run never regenerates secrets
# (regenerating would drift the DB password from the role and rotate the
# session key, logging everyone out). The DB password is generated here and
# reused in the env file below, so the two can never disagree.
if [[ -f /etc/$APP_NAME.env ]]; then
    echo "✓ /etc/$APP_NAME.env exists — leaving the DB password and secrets untouched"
else
    command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
    DB_PASSWORD=$(openssl rand -hex 24)
    # PARTS__SESSION__KEY must be EXACTLY 16 bytes (ADR-0007) or the app refuses
    # to boot. 16 alphanumeric chars = 16 bytes ≈ 95 bits of entropy. (Do NOT
    # use `openssl rand -hex 8`: that is 16 chars but only 64 bits of real
    # entropy — the AES cookie store would rest on a brute-forceable secret.)
    SESSION_KEY=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)

    # Create the role only if absent, but ALWAYS (re)set its password to the
    # value written into the env file below. A bare CREATE USER that hits an
    # already-existing role silently changes nothing — leaving the role on a
    # stale password that mismatches the freshly-written env and fails auth at
    # boot. The ALTER (fed via stdin, so the password never lands in argv/ps)
    # force-syncs the two. The database is created only if absent, never dropped.
    role_exists=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$APP_NAME'")
    [[ "$role_exists" == 1 ]] || sudo -u postgres psql -c "CREATE ROLE $APP_NAME"
    printf "ALTER ROLE %s WITH LOGIN CREATEROLE PASSWORD '%s';\n" "$APP_NAME" "$DB_PASSWORD" \
        | sudo -u postgres psql
    db_exists=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${APP_NAME}_prod'")
    [[ "$db_exists" == 1 ]] || sudo -u postgres psql -c "CREATE DATABASE ${APP_NAME}_prod OWNER $APP_NAME"

    cat >/etc/$APP_NAME.env <<EOF
PARTS__ENV=prod

# Database — generated to match the postgres role created above.
PARTS__DB__PASSWORD=$DB_PASSWORD

# 16-byte key encrypting the auth-session cookie (ADR-0007). Exactly 16 bytes,
# or the app refuses to boot. Regenerate with:
#   LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16
PARTS__SESSION__KEY=$SESSION_KEY

# PDF document fonts (Noto Sans CJK TC; see ADR-0008 and the runbook).
PARTS__RENDER__FONT_DIR=/var/lib/parts/fonts

JAVA_OPTS=-server -Xms512m -Xmx512m

# --- Optional: operator error-alert emails (stays off until all four are set;
#     see docs/runbook.md "Error alerts"). On Hetzner use port 587 (25/465 blocked).
#PARTS__SMTP__HOST=smtp.fastmail.com
#PARTS__SMTP__PORT=587
#PARTS__SMTP__USER=<smtp-login>
#PARTS__SMTP__PASSWORD=<fastmail-app-password>
#PARTS__ALERT__TO=<where alerts go>
#PARTS__ALERT__FROM=<optional; defaults to the SMTP user>

# --- Optional overrides (prod.edn already sets prod defaults) ---
#PARTS__REPL__PORT=7888   # prod nREPL bind port (loopback only)
#PARTS__HTTP__PORT=3000
EOF
    chown root:root /etc/$APP_NAME.env
    chmod 600 /etc/$APP_NAME.env
    echo "✓ Wrote /etc/$APP_NAME.env with generated secrets (root:root, 600)"
fi

# Boxes provisioned before the fonts step have an env file that predates
# PARTS__RENDER__FONT_DIR — append it once. Never rewrites existing
# values, so secrets stay untouched.
if ! grep -q '^PARTS__RENDER__FONT_DIR=' /etc/$APP_NAME.env; then
    printf '\n# PDF document fonts (Noto Sans CJK TC; see ADR-0008 and the runbook).\nPARTS__RENDER__FONT_DIR=%s\n' "$FONT_DIR" >>/etc/$APP_NAME.env
    echo "✓ Appended PARTS__RENDER__FONT_DIR to /etc/$APP_NAME.env"
fi

# 4. ufw
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# 6. systemd unit — every runtime parameter comes from the EnvironmentFile;
# the unit hardcodes nothing tunable. systemd expands $JAVA_OPTS and splits
# it on whitespace into separate java args. The environment (prod/etc.) is
# read from PARTS__ENV in the env file, so no -Dparts.env flag is needed.
cat >/etc/systemd/system/$APP_NAME.service <<EOF
[Unit]
Description=$APP_NAME clojure app
After=network.target postgresql.service

[Service]
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=/etc/$APP_NAME.env
ExecStart=/usr/bin/java \$JAVA_OPTS -jar $APP_DIR/current
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$APP_NAME

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable $APP_NAME

# 7. encrypted backup to Scaleway
apt-get install -y age rclone

# Age recipient (public key) — placeholder; operator must replace with real key
mkdir -p /etc/$APP_NAME
if [[ ! -f /etc/$APP_NAME/backup-recipient.age ]]; then
    cat >/etc/$APP_NAME/backup-recipient.age <<'EOF'
# REPLACE THIS FILE with your age public key (single line starting with "age1...").
# Generate on your laptop with:  age-keygen -o ~/.config/parts-backup/identity.txt
# Then copy the printed "Public key:" line here. The PRIVATE key must NEVER leave your laptop.
EOF
    chmod 644 /etc/$APP_NAME/backup-recipient.age
    echo "⚠️  Created /etc/$APP_NAME/backup-recipient.age placeholder — replace with your age public key"
fi

# rclone config for Scaleway — placeholder; operator must fill in API keys
RCLONE_DIR=/home/$APP_USER/.config/rclone
sudo -u $APP_USER mkdir -p "$RCLONE_DIR"
if [[ ! -f "$RCLONE_DIR/rclone.conf" ]]; then
    sudo -u $APP_USER tee "$RCLONE_DIR/rclone.conf" >/dev/null <<'EOF'
[scaleway]
type = s3
provider = Scaleway
access_key_id = CHANGE_ME
secret_access_key = CHANGE_ME
endpoint = s3.fr-par.scw.cloud
region = fr-par
acl = private
EOF
    sudo -u $APP_USER chmod 600 "$RCLONE_DIR/rclone.conf"
    echo "⚠️  Created $RCLONE_DIR/rclone.conf placeholder — fill in Scaleway API keys"
fi

# Backup script
cat >/usr/local/bin/$APP_NAME-backup <<EOF
#!/usr/bin/env bash
set -euo pipefail

BUCKET="scaleway:$APP_NAME-prod-backup"
RECIPIENT_FILE="/etc/$APP_NAME/backup-recipient.age"
TIMESTAMP="\$(date -u +%Y-%m-%dT%H%M%SZ)"
FILENAME="${APP_NAME}_prod-\${TIMESTAMP}.dump.age"

pg_dump --format=custom --no-owner --no-privileges ${APP_NAME}_prod \\
  | age --recipients-file "\$RECIPIENT_FILE" \\
  | rclone rcat --s3-no-check-bucket --s3-no-head "\${BUCKET}/\${FILENAME}"

echo "Uploaded \${BUCKET}/\${FILENAME}"
EOF
chmod 755 /usr/local/bin/$APP_NAME-backup

# Backup systemd service + daily timer
cat >/etc/systemd/system/$APP_NAME-backup.service <<EOF
[Unit]
Description=Daily encrypted postgres backup to Scaleway
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=oneshot
User=$APP_USER
Group=$APP_USER
ExecStart=/usr/local/bin/$APP_NAME-backup
StandardOutput=journal
StandardError=journal
EOF

cat >/etc/systemd/system/$APP_NAME-backup.timer <<EOF
[Unit]
Description=Trigger $APP_NAME-backup daily

[Timer]
OnCalendar=daily
RandomizedDelaySec=30m
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable $APP_NAME-backup.timer

# 8. caddy — the prod site lives in sites/ alongside any add-instance sites, and
# the main Caddyfile is just the import. So a re-run rewrites only prod's file and
# can't drop the import or clobber an instance's site (add-instance appends the
# same import line; with it already here, that append is a harmless no-op).
mkdir -p /etc/caddy/sites
echo 'import /etc/caddy/sites/*.caddy' >/etc/caddy/Caddyfile
cat >/etc/caddy/sites/parts.caddy <<EOF
$DOMAIN {
    reverse_proxy 127.0.0.1:3000
    encode gzip
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "strict-origin-when-cross-origin"
    }
}
EOF

if [[ "$DOMAIN" == parts.example.com ]]; then
    echo "⚠️  DOMAIN is still 'parts.example.com' — set DOMAIN=your.domain and re-run;" >&2
    echo "    Caddy cannot obtain a certificate for the placeholder hostname." >&2
fi

caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy

# 9. logs
# mulog emits one JSON object per line to stdout, which systemd captures in journald.
# Common viewing commands:
#   journalctl -u $APP_NAME -f                 # live tail
#   journalctl -u $APP_NAME --since "1 hour ago"
#   journalctl -t $APP_NAME -o cat | jq .      # pretty-print structured events
#   journalctl -u $APP_NAME -p err             # error-priority lines only
#   journalctl -u $APP_NAME-backup --since "1 day ago"  # backup runs

# lnav format for mu-log JSON — lets `journalctl -u parts -o cat | lnav` render
# structured events instead of raw JSON. Installed for the admin user, who is
# the interactive SSH login. Keep in sync with scripts/parts.lnav.json.
LNAV_DIR=/home/$ADMIN_USER/.lnav/formats/installed
sudo -u "$ADMIN_USER" mkdir -p "$LNAV_DIR"
sudo -u "$ADMIN_USER" tee "$LNAV_DIR/parts.json" >/dev/null <<'EOF'
{
  "parts_mulog": {
    "title": "Parts mu-log JSON",
    "description": "Structured event log from the Parts Clojure app (mu-log :console-json publisher). Declares only mu-log's stable skeleton; all other event fields are auto-discovered by lnav and stay queryable via SQL.",
    "json": true,
    "timestamp-field": "mulog~1timestamp",
    "timestamp-divisor": 1000,
    "body-field": "mulog~1event-name",
    "value": {
      "mulog~1event-name": {"kind": "string", "identifier": true},
      "env":               {"kind": "string", "identifier": true}
    },
    "line-format": [
      {"field": "__timestamp__"},
      "  ",
      {"field": "env", "default-value": "?", "max-width": 4},
      "  ",
      {"field": "mulog~1event-name"}
    ],
    "sample": [
      {"line": "{\"mulog/event-name\":\"aps.parts.server/starting-server\",\"mulog/timestamp\":1747140000000,\"mulog/trace-id\":\"abc123def456\",\"mulog/namespace\":\"aps.parts.server\",\"port\":3000,\"app-name\":\"Parts\",\"version\":\"0.1.0-SNAPSHOT\",\"env\":\"prod\"}"},
      {"line": "{\"mulog/event-name\":\"aps.parts.middleware/request\",\"mulog/timestamp\":1747140001000,\"mulog/namespace\":\"aps.parts.middleware\",\"app-name\":\"Parts\",\"version\":\"0.1.0-SNAPSHOT\",\"env\":\"prod\",\"user-id\":null,\"authenticated?\":false,\"info\":{\"uri\":\"/\",\"request-method\":\"get\",\"query-params\":null,\"remote-addr\":\"203.0.113.7\",\"user-agent\":\"curl/8.0\"}}"}
    ]
  }
}
EOF

echo "✓ Logs:        journalctl -u $APP_NAME -f"
echo "✓ Backup logs: journalctl -u $APP_NAME-backup --since '1 day ago'"
echo "✓ lnav:        journalctl -u $APP_NAME -o cat -f | lnav"

# 10. SSH hardening
# Write the hardening config now, but DON'T reload sshd — sshd only reads this
# file on reload/restart, so it sits inert until the operator activates it
# after verifying admin login works (see banner below). This keeps the
# bootstrap run itself from ever locking you out of a fresh server.
cat >/etc/ssh/sshd_config.d/99-hardening.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
EOF
chmod 644 /etc/ssh/sshd_config.d/99-hardening.conf

cat <<EOF

════════════════════════════════════════════════════════════════════
  SSH HARDENING — finish this now, before closing your root session
════════════════════════════════════════════════════════════════════
  The hardening config is written to
  /etc/ssh/sshd_config.d/99-hardening.conf but is NOT active yet —
  sshd will not read it until reloaded.

  1. Set a password for the admin user (sudo needs one to authenticate):
       passwd $ADMIN_USER

  2. In a SEPARATE terminal, confirm you can log in and escalate:
       ssh $ADMIN_USER@<this-host>
       sudo whoami          # must print: root

  3. ONLY after step 2 succeeds, activate the hardening:
       systemctl reload ssh

  4. From a THIRD fresh connection, confirm that
     ssh $ADMIN_USER@<this-host> works and ssh root@<this-host> is refused.

  Keep this root session open until step 4 is verified — it is your
  fallback if anything goes wrong.
════════════════════════════════════════════════════════════════════
EOF
