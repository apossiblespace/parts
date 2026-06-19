#!/usr/bin/env bash
#
# Verify the Scaleway backup bucket honours the published 30-day retention
# promise (Privacy Policy / DPA: erasure propagates through backups within
# 30 days). task-047 is a STANDING check — run it at backup setup and
# periodically.
#
# Run it WHERE THE rclone REMOTE IS CONFIGURED — the prod box's `scaleway`
# remote, or your own machine with the backup credentials in rclone.conf
# (or RCLONE_CONFIG pointing at one). It needs only LIST access (it reads
# object *names*, not contents or metadata). With no remote it ABORTS rather
# than pretending to pass.
#
# Usage:
#   scripts/verify-backup-retention.sh [remote:bucket]   # default scaleway:parts-prod-backup
#
# Exit codes:
#   0  passed
#   1  retention violated (artifact older than the window, or versioning on)
#   2  could not verify (no rclone, unreachable bucket, or listing error)

set -euo pipefail

BUCKET="${1:-scaleway:parts-prod-backup}"

# The published promise is 30 days. But S3-style lifecycle expiration is
# day-boundary-aligned (creation rounded down to midnight UTC, +30d) and swept
# asynchronously, so the oldest object routinely lingers several hours — up to a
# day or so — past its 30-day mark before Scaleway actually removes it. An exact
# 30-day cutoff would therefore flag that object on most days during the normal
# lag window. The grace margin makes a FAIL mean "meaningfully past the promise"
# (lifecycle rule missing or broken), not "Scaleway hasn't swept yet".
RETENTION_DAYS=30
GRACE_DAYS=2
MAX_AGE_DAYS=$((RETENTION_DAYS + GRACE_DAYS))

command -v rclone >/dev/null 2>&1 || { echo "✗ rclone not found on PATH — cannot verify." >&2; exit 2; }

echo "Backup retention check — ${BUCKET} (${RETENTION_DAYS}-day promise, +${GRACE_DAYS}d grace for lifecycle lag)"
echo

# Preflight: list the bucket (needs only LIST permission). A check that cannot
# see its target must ABORT, never report success. rclone's own error prints to
# the terminal; we never fold stderr into the data we parse.
if ! all="$(rclone lsf --files-only "${BUCKET}/")"; then
    echo "✗ cannot reach ${BUCKET} — verification aborted (nothing was checked)." >&2
    echo "  (rclone's error is above.) Run where the rclone remote is configured —" >&2
    echo "  the prod box, or your own creds in rclone.conf." >&2
    exit 2
fi

if [[ -z "$all" ]]; then count=0; else count="$(printf '%s\n' "$all" | wc -l | tr -d ' ')"; fi
echo "  ${count} backup artifact(s) in the bucket"
[[ "$count" -eq 0 ]] && echo "⚠ bucket is empty — are backups running? (systemctl status parts-backup.timer)" >&2

fail=0

# 1. No artifact older than the window. Age is read from the FILENAME timestamp
#    (…-YYYY-MM-DDTHHMMSSZ.dump.age), reduced to a YYYYMMDDHHMMSS integer and
#    compared numerically: needs only LIST permission (never per-object HeadObject
#    metadata — the append-only backup key can't Head anyway), is locale-proof,
#    and reflects the backup's true logical age, not an object's last-touched time.
if date --version >/dev/null 2>&1; then
    cutoff="$(date -u -d "${MAX_AGE_DAYS} days ago" +%Y-%m-%dT%H%M%SZ)"   # GNU / Linux (prod box)
else
    cutoff="$(date -u -v-"${MAX_AGE_DAYS}"d +%Y-%m-%dT%H%M%SZ)"           # BSD / macOS
fi
cutoff_num="$((10#$(printf '%s' "$cutoff" | tr -dc 0-9)))"

stale=""; unknown=""
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    ts="$(printf '%s' "$f" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{6}Z' | head -1 || true)"
    if [[ -z "$ts" ]]; then
        unknown+="      ${f}"$'\n'
    elif (( 10#$(printf '%s' "$ts" | tr -dc 0-9) < cutoff_num )); then
        stale+="      ${f}"$'\n'
    fi
done <<< "$all"

if [[ -n "$stale" ]]; then
    { echo "✗ FAIL: artifacts older than ${MAX_AGE_DAYS} days exist (cutoff ${cutoff}):"
      printf '%s' "$stale"; } >&2
    fail=1
else
    echo "✓ no artifact older than ${MAX_AGE_DAYS} days (cutoff ${cutoff})"
fi
[[ -n "$unknown" ]] && { echo "⚠ files whose age couldn't be read from the name:" >&2; printf '%s' "$unknown" >&2; }

# 2. Versioning must be off (best-effort: needs GetBucketVersioning permission /
#    backend support). If it can't be read, WARN — never a silent pass.
if versioning="$(rclone backend versioning "${BUCKET}" 2>/dev/null)"; then
    if [[ "$versioning" == *Enabled* ]]; then
        echo "✗ FAIL: bucket versioning is Enabled — old versions linger past the window" >&2
        fail=1
    else
        echo "✓ versioning not enabled (${versioning:-suspended/unset})"
    fi
else
    echo "⚠ could not read versioning state — confirm manually in the Scaleway console (must be OFF)" >&2
fi

# Object-lock can only be set when a bucket is created; a bucket made without it
# can never acquire it — confirm once in the console at setup (rclone can't read it).

echo
if [[ "$fail" -ne 0 ]]; then
    echo "Retention check FAILED — see docs/runbook.md (Backups & retention)." >&2
    exit 1
fi
echo "Retention check passed."
