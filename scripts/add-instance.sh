#!/usr/bin/env bash
set -euo pipefail

# Provision an additional Parts app instance alongside the one created by
# bootstrap-prod.sh. Each instance gets its own systemd service, postgres
# database + role, environment file, release symlink and Caddy site block
# (gated behind HTTP basic auth).
#
# Run as root on the server. Idempotent — safe to re-run; the postgres
# role password and PARTS__AUTH__SECRET are generated once and preserved.
#
#   add-instance.sh <instance> <domain> <port>
#   add-instance.sh dev staging.your-domain.com 3001
#
# Basic-auth credentials are read from the environment when present:
#   BASIC_AUTH_USER      defaults to the instance name
#   BASIC_AUTH_PASSWORD  if unset, a placeholder hash is written and you
#                        must replace it before the site will load

if [[ $EUID -ne 0 ]]; then
    echo "Must run as root" >&2
    exit 1
fi

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <instance> <domain> <port>" >&2
    echo "   eg: $0 dev staging.your-domain.com 3001" >&2
    exit 1
fi

INSTANCE=$1
DOMAIN=$2
PORT=$3

APP_NAME=parts
APP_USER=parts
APP_DIR=/opt/$APP_NAME
SERVICE=$APP_NAME-$INSTANCE
DB_NAME=${APP_NAME}_${INSTANCE}
DB_USER=${APP_NAME}_${INSTANCE}
ENV_FILE=/etc/$SERVICE.env
BASIC_AUTH_USER=${BASIC_AUTH_USER:-$INSTANCE}

# Secrets are generated once, on the first run for this instance. A re-run
# must NOT regenerate them: the DB password would drift from the postgres
# role, and a rotated PARTS__AUTH__SECRET would invalidate live sessions.
# Existence of $ENV_FILE is the "already provisioned" signal.
if [[ -f "$ENV_FILE" ]]; then
    FIRST_RUN=false
    echo "✓ $ENV_FILE exists — leaving its secrets and the postgres role untouched"
else
    FIRST_RUN=true
    command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
    DB_PASSWORD=$(openssl rand -hex 24)
    AUTH_SECRET=$(openssl rand -hex 32)
fi

# 1. postgres — a separate database AND role, so a leaked dev-instance
# password can't authenticate against the prod database. The parts role's
# CREATEROLE grant (from bootstrap-prod.sh) is what lets this run.
if [[ "$FIRST_RUN" == true ]]; then
    sudo -u postgres psql <<SQL || true
CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';
CREATE DATABASE $DB_NAME OWNER $DB_USER;
SQL
fi

# 2. environment file — written once, with the generated secrets already
# filled in. Every runtime parameter lives here (12-factor): PARTS__ENV
# selects the config base, PARTS__HTTP__PORT the bind port, JAVA_OPTS the
# JVM flags. root:root 600 is enough — systemd reads EnvironmentFile as
# root before dropping to the parts user.
if [[ "$FIRST_RUN" == true ]]; then
    cat >"$ENV_FILE" <<EOF
PARTS__ENV=prod
PARTS__HTTP__PORT=$PORT
PARTS__DB__NAME=$DB_NAME
PARTS__DB__USER=$DB_USER
PARTS__DB__PASSWORD=$DB_PASSWORD
PARTS__AUTH__SECRET=$AUTH_SECRET
JAVA_OPTS=-server -Xms256m -Xmx256m
EOF
    chown root:root "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "✓ Wrote $ENV_FILE with generated secrets (root:root, 600)"
fi

# 3. systemd unit — mirrors parts.service but with its own env file,
# release symlink ($APP_DIR/current-$INSTANCE) and journal identifier.
# The unit hardcodes nothing tunable: JAVA_OPTS (JVM flags), PARTS__ENV and
# PARTS__HTTP__PORT all come from $ENV_FILE. systemd expands $JAVA_OPTS and
# splits it on whitespace into separate args.
cat >"/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=$APP_NAME app instance: $INSTANCE
After=network.target postgresql.service

[Service]
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java \$JAVA_OPTS -jar $APP_DIR/current-$INSTANCE
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SERVICE

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE"

# 4. caddy — bootstrap-prod.sh writes a single /etc/caddy/Caddyfile.
# Switch it to importing per-instance site files so instances can be
# added without ever rewriting that file. The import line is added once.
mkdir -p /etc/caddy/sites
grep -qF 'import /etc/caddy/sites/' /etc/caddy/Caddyfile 2>/dev/null ||
    echo 'import /etc/caddy/sites/*.caddy' >>/etc/caddy/Caddyfile

# Resolve the basic-auth password hash. Caddy needs a bcrypt hash, not the
# plaintext — `caddy hash-password` produces one.
if [[ -n "${BASIC_AUTH_PASSWORD:-}" ]]; then
    BASIC_AUTH_HASH=$(caddy hash-password --plaintext "$BASIC_AUTH_PASSWORD")
else
    BASIC_AUTH_HASH='REPLACE_WITH_OUTPUT_OF__caddy_hash-password'
    echo "⚠️  BASIC_AUTH_PASSWORD not set — wrote a placeholder hash into the site file"
fi

cat >"/etc/caddy/sites/$SERVICE.caddy" <<EOF
$DOMAIN {
    # basic_auth is the Caddy v2.8+ directive name (older versions: basicauth).
    basic_auth {
        $BASIC_AUTH_USER $BASIC_AUTH_HASH
    }

    reverse_proxy 127.0.0.1:$PORT
    encode gzip

    header {
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "strict-origin-when-cross-origin"
        # Keep the dev instance out of search results.
        X-Robots-Tag "noindex, nofollow"
    }
}
EOF

caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy

# 5. next steps
cat <<EOF

════════════════════════════════════════════════════════════════════
  Instance "$INSTANCE" provisioned — finish these steps
════════════════════════════════════════════════════════════════════
  1. Release:   drop a build into $APP_DIR/releases and point the symlink
                  ln -sfn $APP_DIR/releases/<jar> $APP_DIR/current-$INSTANCE
  2. Start:     systemctl start $SERVICE
  3. Logs:      journalctl -u $SERVICE -f

  Note: the daily backup timer only dumps the prod database. Extend
  $APP_NAME-backup if this instance's data also needs backing up.
════════════════════════════════════════════════════════════════════
EOF
