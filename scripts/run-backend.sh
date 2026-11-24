#!/usr/bin/env sh
# Profile B: run the backend (REST API and web pages, browser: http://127.0.0.1:8080) with plain Java against services you run yourself (Ollama, Whisper, Piper; the database is embedded H2 in data/journal.mv.db).
# Usage: scripts/run-backend.sh [--build]     (--build forces a Maven build; it also runs when the jar is missing)
# Reads .env in the repository root; variables already set in the environment win. See docs/CONFIG.md.
set -eu
. "$(dirname "$0")/run-common.sh"
import_dotenv

set_default JOURNAL_STT_PROVIDER whisper
set_default JOURNAL_LLM_PROVIDER ollama
set_default JOURNAL_EMBEDDING_PROVIDER ollama
set_default JOURNAL_VECTOR_PROVIDER jdbc
set_default JOURNAL_AUDIO_PROVIDER filesystem
set_default JOURNAL_TTS_PROVIDER piper
set_default JOURNAL_STT_WHISPER_BASE_URL http://localhost:8000
set_default JOURNAL_LLM_OLLAMA_BASE_URL http://localhost:11434
set_default JOURNAL_EMBEDDING_OLLAMA_BASE_URL http://localhost:11434
set_default JOURNAL_TTS_PIPER_BASE_URL http://localhost:5000
set_default JOURNAL_AUDIO_FILESYSTEM_DIRECTORY "$REPO_ROOT/data/audio"
mkdir -p "$JOURNAL_AUDIO_FILESYSTEM_DIRECTORY"

JAR="$REPO_ROOT/backend/target/quarkus-app/quarkus-run.jar"
if [ "${1:-}" = "--build" ] || [ ! -f "$JAR" ]; then
  echo "==> Building backend"
  mvn_run -pl backend -am package -DskipTests
fi
need_file "$JAR" "Build failed?"
echo "==> Starting backend on http://127.0.0.1:${JOURNAL_HTTP_PORT:-8080}"
exec "$(java_bin)" -jar "$JAR"
