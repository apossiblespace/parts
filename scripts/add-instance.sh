#!/usr/bin/env bash
set -euo pipefail

# Provision an additional Parts app instance alongside the one created by
# bootstrap-prod.sh. Each instance gets its own systemd service, postgres
# database + role, environment file, release symlink, plus its own
# oauth2-proxy sidecar that gates the SPA shell behind GitHub
# (`apossiblespace` org) membership. The Caddy site file routes /api/*
# straight to the JVM (the app's own session auth is the only gate there, so
# XHRs always see JSON 401s — never HTML redirects) and everything else through
# oauth2-proxy, which proxies to the JVM when authenticated and redirects
# to its own sign-in page when not.
#
# Run as root on the server. Idempotent — safe to re-run; the postgres
# role password, PARTS__SESSION__KEY, and oauth2-proxy COOKIE_SECRET are
# generated once and preserved across re-runs.
#
#   add-instance.sh <instance> <domain> <port>
#   add-instance.sh dev staging.your-domain.com 3001
#
# GitHub OAuth App credentials are read from the environment when present;
# otherwise the env file is written with placeholders and the next-steps
# banner tells you how to fill them in.
#   OAUTH_CLIENT_ID, OAUTH_CLIENT_SECRET

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

# oauth2-proxy lives on PORT+1000: instance on 3001 → sidecar on 4001.
OAUTH2_PORT=$((PORT + 1000))
OAUTH2_PROXY_ENV_FILE=/etc/$SERVICE-oauth2-proxy.env
OAUTH2_PROXY_SERVICE=$SERVICE-oauth2-proxy
# Pin the oauth2-proxy version so re-runs install the same binary. Check
# https://github.com/oauth2-proxy/oauth2-proxy/releases when bumping; the
# checksum verification below catches a stale URL.
OAUTH2_PROXY_VERSION=v7.15.3
# GitHub org used as the access list. Add/remove members at
# https://github.com/orgs/apossiblespace/people to grant/revoke staging access.
OAUTH_ORG=apossiblespace

# Secrets are generated once, on the first run for this instance. A re-run
# must NOT regenerate them: the DB password would drift from the postgres
# role, a rotated PARTS__SESSION__KEY would invalidate everyone's session
# cookie, and a rotated COOKIE_SECRET would log every staging user out.
# Existence of $ENV_FILE is the "already provisioned" signal.
if [[ -f "$ENV_FILE" ]]; then
    FIRST_RUN=false
    echo "✓ $ENV_FILE exists — leaving its secrets and the postgres role untouched"
else
    FIRST_RUN=true
    command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
    DB_PASSWORD=$(openssl rand -hex 24)
    # PARTS__SESSION__KEY must be EXACTLY 16 bytes (ADR-0007) or the app refuses
    # to boot. 16 alphanumeric chars = 16 bytes ≈ 95 bits of entropy. (Do NOT use
    # `openssl rand -hex 8`: 16 chars but only 64 bits — a brute-forceable key.)
    SESSION_KEY=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)
    # oauth2-proxy COOKIE_SECRET must be exactly 16, 24, or 32 bytes.
    COOKIE_SECRET=$(openssl rand -base64 32 | head -c 32)
fi

# 1. Install oauth2-proxy from GitHub release (idempotent — skips when
#    the right version is already installed). oauth2-proxy isn't in
#    Ubuntu's repos, so we can't apt-install it.
INSTALLED_VERSION=
if [[ -x /usr/local/bin/oauth2-proxy ]]; then
    if version_output=$(/usr/local/bin/oauth2-proxy --version 2>&1); then
        INSTALLED_VERSION=$(echo "$version_output" | awk '/oauth2-proxy/ {print $2}')
    fi
fi

if [[ "$INSTALLED_VERSION" != "$OAUTH2_PROXY_VERSION" ]]; then
    case "$(uname -m)" in
        x86_64)  ARCH=linux-amd64 ;;
        aarch64) ARCH=linux-arm64 ;;
        *)       echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
    esac
    ASSET=oauth2-proxy-${OAUTH2_PROXY_VERSION}.${ARCH}
    BASE_URL=https://github.com/oauth2-proxy/oauth2-proxy/releases/download/${OAUTH2_PROXY_VERSION}

    workdir=$(mktemp -d)
    trap 'rm -rf "$workdir"' EXIT

    # Save the tarball under its ORIGINAL name so the checksum line (which
    # names that file) verifies against the file we actually downloaded.
    curl -fSL -o "$workdir/${ASSET}.tar.gz" "$BASE_URL/${ASSET}.tar.gz"
    # oauth2-proxy publishes a PER-ASSET checksum file (there is no combined
    # sha256sum.txt). Use the one ending ".tar.gz-sha256sum.txt" — NOT the
    # "<asset>-sha256sum.txt" without ".tar.gz", which checksums the extracted
    # binary, not the tarball we're about to verify.
    curl -fSL -o "$workdir/sha256sum.txt"   "$BASE_URL/${ASSET}.tar.gz-sha256sum.txt"

    # Verify checksum before installing — we're about to run this binary
    # under systemd with access to env-file secrets.
    (cd "$workdir" && grep " ${ASSET}.tar.gz$" sha256sum.txt | sha256sum -c -)

    tar -xzf "$workdir/${ASSET}.tar.gz" -C "$workdir"
    install -m 0755 -o root -g root \
        "$workdir/${ASSET}/oauth2-proxy" /usr/local/bin/oauth2-proxy

    echo "✓ Installed oauth2-proxy $OAUTH2_PROXY_VERSION at /usr/local/bin/oauth2-proxy"
fi

# 2. postgres — a separate database AND role, so a leaked dev-instance
# password can't authenticate against the prod database. The parts role's
# CREATEROLE grant (from bootstrap-prod.sh) is what lets this run.
if [[ "$FIRST_RUN" == true ]]; then
    # Create the role only if absent, but ALWAYS (re)set its password to the
    # value written into the env file below — a CREATE USER that hits an existing
    # role silently changes nothing, leaving the role password stale and out of
    # sync with the env (which fails auth at boot). The database is created only
    # if absent.
    role_exists=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'")
    [[ "$role_exists" == 1 ]] || sudo -u postgres psql -c "CREATE ROLE $DB_USER"
    printf "ALTER ROLE %s WITH LOGIN PASSWORD '%s';\n" "$DB_USER" "$DB_PASSWORD" \
        | sudo -u postgres psql
    db_exists=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'")
    [[ "$db_exists" == 1 ]] || sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER"
fi

# 3. app environment file — written once, with the generated secrets
# already filled in. Every runtime parameter lives here (12-factor):
# PARTS__ENV selects the config base, PARTS__HTTP__PORT the bind port,
# JAVA_OPTS the JVM flags. root:root 600 is enough — systemd reads
# EnvironmentFile as root before dropping to the parts user.
if [[ "$FIRST_RUN" == true ]]; then
    cat >"$ENV_FILE" <<EOF
PARTS__ENV=prod
PARTS__HTTP__PORT=$PORT
PARTS__DB__NAME=$DB_NAME
PARTS__DB__USER=$DB_USER
PARTS__DB__PASSWORD=$DB_PASSWORD
PARTS__SESSION__KEY=$SESSION_KEY
JAVA_OPTS=-server -Xms256m -Xmx256m
EOF
    chown root:root "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "✓ Wrote $ENV_FILE with generated secrets (root:root, 600)"
fi

# 4. app systemd unit — mirrors parts.service but with its own env file,
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

# 5. oauth2-proxy env file — one per instance. CLIENT_ID/SECRET come from
# the operator's environment when set; otherwise placeholders that the
# next-steps banner explains how to fill in. Like $ENV_FILE, this is
# generated once and preserved on re-runs.
if [[ "$FIRST_RUN" == true ]]; then
    OAUTH_CLIENT_ID_VALUE=${OAUTH_CLIENT_ID:-REPLACE_WITH_GITHUB_OAUTH_APP_CLIENT_ID}
    OAUTH_CLIENT_SECRET_VALUE=${OAUTH_CLIENT_SECRET:-REPLACE_WITH_GITHUB_OAUTH_APP_CLIENT_SECRET}

    cat >"$OAUTH2_PROXY_ENV_FILE" <<EOF
OAUTH2_PROXY_PROVIDER=github
OAUTH2_PROXY_GITHUB_ORG=$OAUTH_ORG
OAUTH2_PROXY_CLIENT_ID=$OAUTH_CLIENT_ID_VALUE
OAUTH2_PROXY_CLIENT_SECRET=$OAUTH_CLIENT_SECRET_VALUE
OAUTH2_PROXY_COOKIE_SECRET=$COOKIE_SECRET
OAUTH2_PROXY_COOKIE_SECURE=true
OAUTH2_PROXY_HTTP_ADDRESS=127.0.0.1:$OAUTH2_PORT
OAUTH2_PROXY_REDIRECT_URL=https://$DOMAIN/oauth2/callback
OAUTH2_PROXY_REVERSE_PROXY=true
OAUTH2_PROXY_EMAIL_DOMAINS=*
OAUTH2_PROXY_UPSTREAMS=http://127.0.0.1:$PORT
OAUTH2_PROXY_SKIP_PROVIDER_BUTTON=true
OAUTH2_PROXY_WHITELIST_DOMAINS=$DOMAIN
EOF
    chown root:root "$OAUTH2_PROXY_ENV_FILE"
    chmod 600 "$OAUTH2_PROXY_ENV_FILE"

    if [[ -z "${OAUTH_CLIENT_ID:-}" || -z "${OAUTH_CLIENT_SECRET:-}" ]]; then
        echo "⚠️  OAUTH_CLIENT_ID / OAUTH_CLIENT_SECRET unset — placeholders in $OAUTH2_PROXY_ENV_FILE need replacing before $OAUTH2_PROXY_SERVICE will start cleanly"
    else
        echo "✓ Wrote $OAUTH2_PROXY_ENV_FILE with GitHub OAuth creds (root:root, 600)"
    fi
fi

# 6. oauth2-proxy systemd unit — one per instance, listening on
# 127.0.0.1:$OAUTH2_PORT. Caddy is the only thing that needs to reach it,
# so localhost-only is sufficient.
cat >"/etc/systemd/system/$OAUTH2_PROXY_SERVICE.service" <<EOF
[Unit]
Description=oauth2-proxy for $SERVICE
After=network.target

[Service]
Type=simple
User=$APP_USER
EnvironmentFile=$OAUTH2_PROXY_ENV_FILE
ExecStart=/usr/local/bin/oauth2-proxy
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$OAUTH2_PROXY_SERVICE

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE"
systemctl enable "$OAUTH2_PROXY_SERVICE"

# 7. caddy — bootstrap-prod.sh writes a single /etc/caddy/Caddyfile.
# Switch it to importing per-instance site files so instances can be
# added without ever rewriting that file. The import line is added once.
mkdir -p /etc/caddy/sites
grep -qF 'import /etc/caddy/sites/' /etc/caddy/Caddyfile 2>/dev/null ||
    echo 'import /etc/caddy/sites/*.caddy' >>/etc/caddy/Caddyfile

# Caddy reduced to routing: /api/* goes straight to the JVM (the app's own
# session auth is the only gate, so XHRs always see JSON 401s — never HTML
# redirects). Everything else routes through oauth2-proxy, which gates
# on GitHub org membership and proxies to the JVM when authenticated.
# Redirect-to-login is handled natively by oauth2-proxy; no Caddy
# forward_auth + handle_response gymnastics.
cat >"/etc/caddy/sites/$SERVICE.caddy" <<EOF
$DOMAIN {
    encode gzip

    header {
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "strict-origin-when-cross-origin"
        # Keep the instance out of search results.
        X-Robots-Tag "noindex, nofollow"
    }

    handle /api/* {
        reverse_proxy 127.0.0.1:$PORT
    }

    handle {
        reverse_proxy 127.0.0.1:$OAUTH2_PORT
    }
}
EOF

caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy

# 8. next steps
CALLBACK_URL=https://$DOMAIN/oauth2/callback
cat <<EOF

════════════════════════════════════════════════════════════════════
  Instance "$INSTANCE" provisioned — finish these steps
════════════════════════════════════════════════════════════════════
  1. Release:   drop a build into $APP_DIR/releases and point the symlink
                  ln -sfn $APP_DIR/releases/<jar> $APP_DIR/current-$INSTANCE
  2. Start app: systemctl start $SERVICE

  3. GitHub OAuth App (skip if already registered for this domain):
       Register at
         https://github.com/organizations/$OAUTH_ORG/settings/applications/new
       Use:
         Homepage URL:               https://$DOMAIN
         Authorization callback URL: $CALLBACK_URL
       Then edit $OAUTH2_PROXY_ENV_FILE and replace the
       OAUTH2_PROXY_CLIENT_ID / OAUTH2_PROXY_CLIENT_SECRET placeholders.
       Or re-run this script with OAUTH_CLIENT_ID / OAUTH_CLIENT_SECRET
       exported, after first removing $OAUTH2_PROXY_ENV_FILE to force a
       regeneration.

  4. Start sidecar: systemctl start $OAUTH2_PROXY_SERVICE

  5. Logs:
       App:           journalctl -u $SERVICE -f
       oauth2-proxy:  journalctl -u $OAUTH2_PROXY_SERVICE -f

  Access is gated by membership in the $OAUTH_ORG GitHub org. Manage
  access at https://github.com/orgs/$OAUTH_ORG/people.

  Note: the daily backup timer only dumps the prod database. Extend
  $APP_NAME-backup if this instance's data also needs backing up.
════════════════════════════════════════════════════════════════════
EOF
