#!/usr/bin/env sh
# Profile B: run the frontend with plain Java. The backend must already be running (scripts/run-backend.sh).
# Usage: scripts/run-frontend.sh [--build]    (first production build downloads npm packages, takes a few minutes)
# BACKEND_URL (default http://127.0.0.1:8080), JOURNAL_FRONTEND_PORT (default 8081), JOURNAL_HTTP_HOST (default 127.0.0.1).
set -eu
. "$(dirname "$0")/run-common.sh"
import_dotenv

JAR="$REPO_ROOT/frontend/target/app.jar"
if [ "${1:-}" = "--build" ] || [ ! -f "$JAR" ]; then
  echo "==> Building frontend (production)"
  mvn_run -pl frontend -am package -DskipTests -Pproduction
fi
need_file "$JAR" "Build failed?"
echo "==> Starting frontend on http://127.0.0.1:${JOURNAL_FRONTEND_PORT:-8081} (backend: ${BACKEND_URL:-http://127.0.0.1:8080})"
exec "$(java_bin)" -jar "$JAR"
