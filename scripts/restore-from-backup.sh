#!/usr/bin/env bash
#
# Restore an age-encrypted postgres backup downloaded from your offsite storage
# provider into a local postgres instance, for restore drills or actual incident
# recovery.
#
# This script is intended to run on your dev machine. The age private key
# required to decrypt the backup must never live on the production server.
#
# Usage:
#   scripts/restore-from-backup.sh <path-to-encrypted-dump.age> [target-db-name]
#
# Example:
#   scripts/restore-from-backup.sh \
#     ~/Downloads/parts_prod-2026-05-13T223558Z.dump.age \
#     parts_restore_test

set -euo pipefail

BACKUP_FILE="${1:?Usage: $0 <encrypted-dump.age> [target-db]}"
TARGET_DB="${2:-parts_restore_test}"
IDENTITY="${AGE_IDENTITY:-$HOME/.config/parts-backup/identity.txt}"

[[ -f "$BACKUP_FILE" ]] || {
    echo "Backup file not found: $BACKUP_FILE" >&2
    exit 1
}
[[ -f "$IDENTITY" ]] || {
    echo "Age identity not found: $IDENTITY" >&2
    exit 1
}

echo "Preparing fresh target database: $TARGET_DB"
dropdb --if-exists "$TARGET_DB"
createdb "$TARGET_DB"

echo "Decrypting and restoring into $TARGET_DB ..."

age -d -i "$IDENTITY" "$BACKUP_FILE" |
    pg_restore --dbname="$TARGET_DB" --no-owner --no-privileges

echo
echo "Restore complete."
