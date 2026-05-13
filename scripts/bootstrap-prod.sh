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
CREATE USER $APP_NAME WITH PASSWORD 'change-me' CREATEROLE;
CREATE DATABASE ${APP_NAME}_prod OWNER $APP_NAME;
EOF

# 4. ufw
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# 5. environment file template
cat >/etc/$APP_NAME.env.example <<EOF
# Application environment
PARTS__ENV=prod

# Database connection (password should be set securely)
PARTS__DB__PASSWORD=change-me

# Authentication secret (MUST be set to a secure random value)
PARTS__AUTH__SECRET=change-me-to-secure-random-string

# Optional: Override default database settings
# PARTS__DB__HOST=localhost
# PARTS__DB__PORT=5432
# PARTS__DB__NAME=parts_prod
# PARTS__DB__USER=parts
# PARTS__DB__SSL=true
EOF

echo "⚠️  Created /etc/$APP_NAME.env.example - copy to /etc/$APP_NAME.env and set secrets"

# 6. systemd unit
cat >/etc/systemd/system/$APP_NAME.service <<EOF
[Unit]
Description=$APP_NAME clojure app
After=network.target postgresql.service

[Service]
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=/etc/$APP_NAME.env
ExecStart=/usr/bin/java -server -Xms512m -Xmx512m -Dparts.env=prod -jar $APP_DIR/current
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

# 8. caddy
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

# 9. logs
# mulog emits one JSON object per line to stdout, which systemd captures in journald.
# Common viewing commands:
#   journalctl -u $APP_NAME -f                 # live tail
#   journalctl -u $APP_NAME --since "1 hour ago"
#   journalctl -t $APP_NAME -o cat | jq .      # pretty-print structured events
#   journalctl -u $APP_NAME -p err             # error-priority lines only
#   journalctl -u $APP_NAME-backup --since "1 day ago"  # backup runs
echo "✓ Logs:        journalctl -u $APP_NAME -f"
echo "✓ Backup logs: journalctl -u $APP_NAME-backup --since '1 day ago'"
