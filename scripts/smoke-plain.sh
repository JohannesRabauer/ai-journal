#!/usr/bin/env sh
# Opt-in smoke test of profile B (plain Java) with FAKE AI providers (no Ollama/Whisper/Piper). Not part of verify.
# Starts only Postgres from docker-compose.yml (throwaway project and data dir), runs scripts/run-backend.sh and
# scripts/run-frontend.sh, checks entry create/list, frontend 200, export, delete-all, then stops everything.
# Needs JDK 25, Docker, curl, unzip (or python3). Mirror of smoke-plain.ps1 (the .ps1 is the tested one).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
mkdir -p "$TMP/db" "$TMP/audio" "$TMP/tts-cache"
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])'; }
DB_PORT="$(free_port)"; BE_PORT="$(free_port)"; FE_PORT="$(free_port)"
unset QUARKUS_PROFILE OPENAI_API_KEY JOURNAL_CLOUD_CONSENT
export COMPOSE_PROJECT_NAME=aidiarysmokeplain
JOURNAL_DB_PASSWORD="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"; export JOURNAL_DB_PASSWORD
export JOURNAL_DB_DATA_DIR="$TMP/db" JOURNAL_DB_HOST_PORT="$DB_PORT"
export JOURNAL_DB_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/ai-diary"
export JOURNAL_STT_PROVIDER=fake JOURNAL_LLM_PROVIDER=fake JOURNAL_EMBEDDING_PROVIDER=fake JOURNAL_TTS_PROVIDER=fake
export JOURNAL_VECTOR_PROVIDER=pgvector JOURNAL_AUDIO_PROVIDER=filesystem
export JOURNAL_AUDIO_FILESYSTEM_DIRECTORY="$TMP/audio" JOURNAL_TTS_CACHE_DIR="$TMP/tts-cache"
BE="http://127.0.0.1:$BE_PORT"; FE="http://127.0.0.1:$FE_PORT"
PIDS=""
cleanup() {
  echo "==> cleanup"
  for p in $PIDS; do kill "$p" 2>/dev/null || true; done
  sleep 2
  (cd "$ROOT" && docker compose down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$TMP"
}
trap cleanup EXIT
fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }
wait_for() { # url seconds
  i=0; while [ "$i" -lt "$2" ]; do curl -fsS -o /dev/null "$1" 2>/dev/null && return 0; i=$((i + 1)); sleep 1; done
  fail "not ready: $1"
}
H='X-AI-Diary: 1'
cd "$ROOT"
echo "==> start Postgres only"
docker compose up -d --build --wait --wait-timeout 300 db
echo "==> run-backend.sh"
JOURNAL_HTTP_PORT="$BE_PORT" sh scripts/run-backend.sh >"$TMP/backend.log" 2>&1 & PIDS="$PIDS $!"
wait_for "$BE/api/system/info" 1500
curl -fsS "$BE/api/system/info" | grep -q '"profile":"full"' || fail "profile is not full"
echo "==> run-frontend.sh"
BACKEND_URL="$BE" JOURNAL_FRONTEND_PORT="$FE_PORT" JOURNAL_HTTP_HOST=127.0.0.1 sh scripts/run-frontend.sh >"$TMP/frontend.log" 2>&1 & PIDS="$PIDS $!"
wait_for "$FE/" 1500
echo "==> entry, export, delete-all"
curl -fsS -X POST -H "$H" -H 'Content-Type: application/json' -d '{"text":"smoke test entry for profile B"}' "$BE/api/entries" | grep -q '"id"' || fail "create entry"
curl -fsS "$BE/api/entries" | grep -q '"total":[1-9]' || fail "entry not listed"
curl -fsS -o "$TMP/export.zip" "$BE/api/data/export"
if command -v unzip >/dev/null 2>&1; then unzip -l "$TMP/export.zip" | grep -q entries.json || fail "no entries.json"; fi
curl -fsS -X POST -H "$H" -H 'Content-Type: application/json' -d '{"confirmation":"DELETE EVERYTHING"}' "$BE/api/data/delete-all" >/dev/null
curl -fsS "$BE/api/entries" | grep -q '"total":0' || fail "journal not empty after delete-all"
echo "==> smoke-plain OK"
