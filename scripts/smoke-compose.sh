#!/usr/bin/env sh
# Opt-in Docker smoke test of profile A (needs Docker and curl; not part of verify). Heavy: builds the backend and
# frontend images. Config check for all profile combinations, build, start db + backend + frontend in a THROWAWAY
# project with a throwaway data dir and fake providers, POST/GET an entry, frontend 200, then down -v.
# The real ./db/data is never used. Uses host ports 25433, 28080, 28081 (override: SMOKE_DB_PORT, SMOKE_BACKEND_PORT, SMOKE_FRONTEND_PORT) on 127.0.0.1.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
mkdir -p "$TMP/db" "$TMP/audio"

export COMPOSE_PROJECT_NAME=aidiarysmoke
JOURNAL_DB_PASSWORD="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export JOURNAL_DB_PASSWORD
export JOURNAL_DB_DATA_DIR="$TMP/db" JOURNAL_AUDIO_DATA_DIR="$TMP/audio"
export JOURNAL_DB_HOST_PORT="${SMOKE_DB_PORT:-25433}" JOURNAL_BACKEND_HOST_PORT="${SMOKE_BACKEND_PORT:-28080}" JOURNAL_FRONTEND_HOST_PORT="${SMOKE_FRONTEND_PORT:-28081}"
export JOURNAL_STT_PROVIDER=fake JOURNAL_LLM_PROVIDER=fake JOURNAL_EMBEDDING_PROVIDER=fake
export JOURNAL_TTS_PROVIDER=fake JOURNAL_VECTOR_PROVIDER=memory JOURNAL_AUDIO_PROVIDER=memory

cleanup() {
  echo "==> cleanup (down -v, remove temp dir)"
  (cd "$ROOT" && docker compose down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$TMP"
}
trap cleanup EXIT
cd "$ROOT"

echo "==> compose config for all profile combinations"
docker compose config --quiet
for p in gpu cpu; do docker compose --profile "$p" config --quiet; docker compose --profile "$p" --profile tts config --quiet; done
echo "    ok"

echo "==> build backend and frontend images"
docker compose build backend frontend
echo "==> start db, backend, frontend (fake providers, throwaway data)"
docker compose up -d --wait --wait-timeout 300 db backend frontend

echo "==> POST and GET an entry through the backend"
curl -fsS -X POST -H 'Content-Type: application/json' -d '{"text":"smoke test entry"}' http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/entries | grep -q '"id"'
curl -fsS http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/entries | grep -q '"total":1'
echo "==> export and frontend"
curl -fsS -o "$TMP/export.zip" http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/data/export
[ "$(wc -c < "$TMP/export.zip")" -gt 100 ]
[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$JOURNAL_FRONTEND_HOST_PORT/)" = "200" ]
echo "==> smoke OK"
