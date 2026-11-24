#!/bin/sh
# One-time import of the OLD PostgreSQL version's data (documents + chat_history) into the embedded H2 file database.
# Usage: sh scripts/import-legacy-postgres.sh <old-data-dir> [h2-url]   (backend stopped; Docker needed for this import only)
set -eu
SRC="${1:?usage: import-legacy-postgres.sh <old-data-dir> [h2-url]}"
H2URL="${2:-jdbc:h2:file:./data/journal;DB_CLOSE_ON_EXIT=FALSE}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
H2="$(find "$HOME/.m2/repository/com/h2database/h2" -name 'h2-*.jar' ! -name '*sources*' ! -name '*javadoc*' | sort | tail -1)"
[ -n "$H2" ] || { echo "H2 jar not found in ~/.m2: run ./mvnw package once." >&2; exit 1; }
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
WORK="$(mktemp -d)"
cp -r "$SRC" "$WORK/pg"
PGDATA=/var/lib/postgresql/data; [ -d "$WORK/pg/pgdata" ] && PGDATA=/var/lib/postgresql/data/pgdata
NAME="diary-import-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=import -e "PGDATA=$PGDATA" -v "$WORK/pg:/var/lib/postgresql/data" postgres:17 >/dev/null
i=0; until docker exec "$NAME" pg_isready -U ai-diary >/dev/null 2>&1; do i=$((i+1)); [ $i -lt 40 ] || { echo "temporary postgres did not start" >&2; exit 1; }; sleep 2; done
# the old documents table has a pgvector column this plain image cannot read: copy the plain columns first
docker exec "$NAME" psql -U ai-diary -d ai-diary -v ON_ERROR_STOP=1 -c 'CREATE TABLE imp_documents AS SELECT id, aicontent, entrytext, "timestamp" AS "TIMESTAMP" FROM documents' >/dev/null
docker exec "$NAME" pg_dump -U ai-diary -d ai-diary --data-only --column-inserts --no-owner -t imp_documents -t chat_history -f /tmp/legacy.sql
docker cp "$NAME:/tmp/legacy.sql" "$WORK/legacy.sql"
{
  cat "$ROOT/backend/src/main/resources/db/migration/V1__baseline.sql"
  awk '/^INSERT INTO /{p=1; sub(/^INSERT INTO public\.imp_documents/,"INSERT INTO documents"); sub(/^INSERT INTO public\./,"INSERT INTO ")} p{print} p&&/\);$/{p=0}' "$WORK/legacy.sql"
  echo "ALTER SEQUENCE documents_seq RESTART WITH 10000;"
  echo "ALTER SEQUENCE chat_history_seq RESTART WITH 10000;"
} > "$WORK/import.sql"
"$JAVA" -cp "$H2" org.h2.tools.RunScript -url "$H2URL" -user journal -script "$WORK/import.sql"
rm -rf "$WORK"
echo "Imported legacy documents and chat_history. Start the backend: it upgrades the old tables to entries on first start."
