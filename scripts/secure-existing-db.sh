#!/usr/bin/env sh
# Secures a PostgreSQL database that was created before v1 with "trust" authentication (no password needed):
#   1. backs the database up (pg_dump, custom format) into BACKUP_DIR (default ./backups),
#   2. sets the password of the ai-diary user to JOURNAL_DB_PASSWORD (from .env or the environment),
#   3. switches every "trust" rule in pg_hba.conf to scram-sha-256 (a copy of the old file is kept) and reloads,
#   4. verifies: the right password works, a wrong or missing password is rejected.
# It never deletes or rewrites data. Docs: docs/PRIVACY.md, "Securing an existing database".
#
# Usage: scripts/secure-existing-db.sh [container-name-or-id]     env: BACKUP_DIR, SKIP_BACKUP=1
# Default container: the compose service "db" of this repository.
set -eu
. "$(dirname "$0")/run-common.sh"
import_dotenv
USER_NAME=ai-diary
DB_NAME=ai-diary

if [ -z "${JOURNAL_DB_PASSWORD:-}" ] || [ "$JOURNAL_DB_PASSWORD" = "change-me-to-a-long-random-value" ]; then
  echo "Set JOURNAL_DB_PASSWORD in .env to a new long random value first (it becomes the database password)." >&2
  exit 1
fi
case "$JOURNAL_DB_PASSWORD" in *"'"*) echo "JOURNAL_DB_PASSWORD must not contain a single quote." >&2; exit 1 ;; esac

CONTAINER="${1:-}"
if [ -z "$CONTAINER" ]; then
  CONTAINER="$(cd "$REPO_ROOT" && docker compose ps -q db | head -n 1)"
  [ -n "$CONTAINER" ] || { echo 'The compose service "db" is not running. Start it (docker compose up -d db) or pass a container name.' >&2; exit 1; }
fi
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/backups}"

psql_exec() { # psql_exec <extra psql args...>   (SQL on stdin)
  docker exec -i -e "PGPASSWORD=$JOURNAL_DB_PASSWORD" "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$USER_NAME" -d "$DB_NAME" "$@"
}

echo "==> Container: $CONTAINER"
if [ "${SKIP_BACKUP:-0}" != "1" ]; then
  mkdir -p "$BACKUP_DIR"
  FILE="$BACKUP_DIR/ai-diary-$(date +%Y%m%d-%H%M%S).dump"
  echo "==> Backup -> $FILE"
  docker exec -e "PGPASSWORD=$JOURNAL_DB_PASSWORD" "$CONTAINER" sh -c "pg_dump -U $USER_NAME -Fc $DB_NAME > /tmp/ai-diary-backup.dump" \
    || { echo "pg_dump failed; nothing was changed." >&2; exit 1; }
  docker cp "$CONTAINER:/tmp/ai-diary-backup.dump" "$FILE"
  docker exec "$CONTAINER" rm -f /tmp/ai-diary-backup.dump
  [ -s "$FILE" ] || { echo "Backup failed; nothing was changed." >&2; exit 1; }
  chmod 600 "$FILE" 2>/dev/null || true
  echo "    Keep this file private: it contains your journal."
fi

HBA="$(echo 'SHOW hba_file;' | psql_exec -At | tr -d '\r')"
echo "==> Setting the password of role $USER_NAME"
echo "ALTER ROLE \"$USER_NAME\" WITH PASSWORD '$JOURNAL_DB_PASSWORD';" | psql_exec >/dev/null

echo "==> Switching trust -> scram-sha-256 in $HBA (backup copy: $HBA.bak-before-secure)"
docker exec "$CONTAINER" sh -c "cp -n '$HBA' '$HBA.bak-before-secure' && sed -i -E '/^[[:space:]]*#/! s/\\btrust\\b/scram-sha-256/g' '$HBA'"
echo 'SELECT pg_reload_conf();' | psql_exec >/dev/null

echo "==> Verifying"
echo 'SELECT 1;' | psql_exec -h 127.0.0.1 -At >/dev/null
echo "    right password: OK"
if echo 'SELECT 1;' | docker exec -i -e PGPASSWORD=definitely-wrong "$CONTAINER" psql -U "$USER_NAME" -d "$DB_NAME" -h 127.0.0.1 -At >/dev/null 2>&1; then
  echo "A wrong password was ACCEPTED: pg_hba.conf still allows trust. Check the file manually." >&2
  exit 1
fi
echo "    wrong password rejected: OK"
echo "==> Done. Restart the backend so it connects with the password (docker compose up -d backend)."
