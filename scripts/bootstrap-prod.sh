#!/usr/bin/env bash
set -euo pipefail

APP_USER=parts
APP_NAME=parts
APP_DIR=/opt/$APP_NAME
DOMAIN=parts.example.com

# 1. basic packages
apt-get update
apt-get install -y \
    curl git ufw \
    openjdk-21-jre-headless \
    postgresql-16 \
    caddy

# 2. app user + dirs
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"

mkdir -p "$APP_DIR/releases"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

# 3. postgres
sudo -u postgres psql <<EOF || true
CREATE USER $APP_NAME WITH PASSWORD 'change-me';
CREATE DATABASE ${APP_NAME}_prod OWNER $APP_NAME;
EOF

# 4. ufw
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# 5. systemd unit
cat >/etc/systemd/system/$APP_NAME.service <<EOF
[Unit]
Description=$APP_NAME clojure app
After=network.target postgresql.service

[Service]
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=/etc/$APP_NAME.env
ExecStart=/usr/bin/java -server -Xms512m -Xmx512m -jar $APP_DIR/current
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable $APP_NAME

# 6. caddy
cat >/etc/caddy/Caddyfile <<EOF
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

caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
