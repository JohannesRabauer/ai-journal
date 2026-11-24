#!/usr/bin/env sh
# Opt-in smoke test of profile B (plain Java) with FAKE AI providers (no Ollama/Whisper/Piper). Not part of verify.
# Uses a throwaway embedded H2 database file (no Docker), runs scripts/run-backend.sh (one JVM: API and pages), checks
# the HTML pages (200, h1, CSP), entry create/list, export, delete-all, then stops everything.
# Needs JDK 25, curl, unzip (or python3). Mirror of smoke-plain.ps1 (the .ps1 is the tested one).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/run-common.sh"
TMP="$(mktemp -d)"
mkdir -p "$TMP/h2" "$TMP/audio" "$TMP/tts-cache"
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])'; }
BE_PORT="$(free_port)"
unset QUARKUS_PROFILE OPENAI_API_KEY JOURNAL_CLOUD_CONSENT
export JOURNAL_DB_URL="jdbc:h2:file:$TMP/h2/journal;DB_CLOSE_ON_EXIT=FALSE"
export JOURNAL_STT_PROVIDER=fake JOURNAL_LLM_PROVIDER=fake JOURNAL_EMBEDDING_PROVIDER=fake JOURNAL_TTS_PROVIDER=fake
export JOURNAL_VECTOR_PROVIDER=jdbc JOURNAL_AUDIO_PROVIDER=filesystem
export JOURNAL_AUDIO_FILESYSTEM_DIRECTORY="$TMP/audio" JOURNAL_TTS_CACHE_DIR="$TMP/tts-cache"
BE="http://127.0.0.1:$BE_PORT"
PIDS=""
cleanup() {
  echo "==> cleanup"
  for p in $PIDS; do kill "$p" 2>/dev/null || true; done
  sleep 2
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
echo "==> run-backend.sh"
JOURNAL_HTTP_PORT="$BE_PORT" sh scripts/run-backend.sh >"$TMP/backend.log" 2>&1 & PIDS="$PIDS $!"
wait_for "$BE/api/system/info" 1500
curl -fsS "$BE/api/system/info" | grep -q '"profile":"full"' || fail "profile is not full"
echo "==> HTML pages served by the same JVM"
assert_html_pages "$BE"
echo "==> entry, export, delete-all"
curl -fsS -X POST -H "$H" -H 'Content-Type: application/json' -d '{"text":"smoke test entry for profile B"}' "$BE/api/entries" | grep -q '"id"' || fail "create entry"
curl -fsS "$BE/api/entries" | grep -q '"total":[1-9]' || fail "entry not listed"
curl -fsS -H 'Accept-Language: en' "$BE/journal" | grep -q 'smoke test entry for profile B' || fail "journal page does not show the entry"
curl -fsS -o "$TMP/export.zip" "$BE/api/data/export"
if command -v unzip >/dev/null 2>&1; then unzip -l "$TMP/export.zip" | grep -q entries.json || fail "no entries.json"; fi
curl -fsS -X POST -H "$H" -H 'Content-Type: application/json' -d '{"confirmation":"DELETE EVERYTHING"}' "$BE/api/data/delete-all" >/dev/null
curl -fsS "$BE/api/entries" | grep -q '"total":0' || fail "journal not empty after delete-all"
echo "==> smoke-plain OK"
