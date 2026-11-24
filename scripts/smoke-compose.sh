#!/usr/bin/env sh
# Opt-in Docker smoke test of profile A (needs Docker and curl; not part of verify). Heavy: builds the backend
# image. Config check for all profile combinations, build, start the backend (API and pages) in a THROWAWAY
# project with a throwaway data dir and fake providers, POST/GET an entry, HTML pages (200, h1, CSP), then down -v.
# The real ./data/db is never used. Uses host port 28080 (override: SMOKE_BACKEND_PORT) on 127.0.0.1.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/run-common.sh"
TMP="$(mktemp -d)"
mkdir -p "$TMP/db" "$TMP/audio"

export COMPOSE_PROJECT_NAME=aidiarysmoke
export JOURNAL_DB_DATA_DIR="$TMP/db" JOURNAL_AUDIO_DATA_DIR="$TMP/audio"
export JOURNAL_BACKEND_HOST_PORT="${SMOKE_BACKEND_PORT:-28080}"
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

echo "==> build the backend image"
docker compose build backend
echo "==> start the backend (fake providers, throwaway data)"
docker compose up -d --wait --wait-timeout 300 backend

echo "==> POST and GET an entry through the backend"
curl -fsS -X POST -H 'X-AI-Diary: 1' -H 'Content-Type: application/json' -d '{"text":"smoke test entry"}' http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/entries | grep -q '"id"'
curl -fsS http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/entries | grep -q '"total":1'
echo "==> export and HTML pages"
curl -fsS -o "$TMP/export.zip" http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT/api/data/export
[ "$(wc -c < "$TMP/export.zip")" -gt 100 ]
assert_html_pages "http://127.0.0.1:$JOURNAL_BACKEND_HOST_PORT"
echo "==> smoke OK"
