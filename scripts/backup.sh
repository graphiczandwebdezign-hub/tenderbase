#!/usr/bin/env bash
# Simple, configurable PostgreSQL backup. Intended to be run from cron, e.g.:
#   0 2 * * *  /app/scripts/backup.sh >> /var/log/tender-backup.log 2>&1
#
# Configuration via environment:
#   DATABASE_URL      postgres connection string (required)
#   BACKUP_DIR        where to write dumps (default: ./backups)
#   BACKUP_KEEP_DAYS  delete dumps older than this many days (default: 14)
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d_%H%M%S)"

mkdir -p "${BACKUP_DIR}"

if [ -z "${DATABASE_URL:-}" ]; then
  echo "DATABASE_URL is not set" >&2
  exit 1
fi

OUT="${BACKUP_DIR}/tenderbase_${STAMP}.sql.gz"
echo "[backup] dumping to ${OUT}"
pg_dump "${DATABASE_URL}" | gzip > "${OUT}"

echo "[backup] pruning dumps older than ${BACKUP_KEEP_DAYS} days"
find "${BACKUP_DIR}" -name 'tenderbase_*.sql.gz' -type f -mtime "+${BACKUP_KEEP_DAYS}" -delete

echo "[backup] done"
