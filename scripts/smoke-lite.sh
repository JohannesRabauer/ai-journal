#!/usr/bin/env sh
# Opt-in smoke test of profile C (lite-cloud) against the MOCKED OpenAI API in scripts/mock-openai. Not part of verify.
# Mirror of smoke-lite.ps1 (the .ps1 is the tested one; this one is untested). Needs JDK 25, Node 18+, curl, python3, unzip.
# Usage: sh scripts/smoke-lite.sh [--skip-build]
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/run-common.sh"
TMP="$(mktemp -d)"; mkdir -p "$TMP/h2" "$TMP/audio" "$TMP/tts-cache"
PHRASE=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE
JAVA="$(java_bin)"
COMMON="-XX:+UseSerialGC -Xss256k -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -Xms32m"
BE_OPTS="-Xmx128m -XX:MaxMetaspaceSize=96m $COMMON"; FE_OPTS="-Xmx160m -XX:MaxMetaspaceSize=128m $COMMON"
BE_JAR="$ROOT/backend/target/lite-app/quarkus-run.jar"; FE_JAR="$ROOT/frontend/target/app.jar"
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])'; }
PIDS=""
cleanup() { echo "==> cleanup"; for p in $PIDS; do kill "$p" 2>/dev/null || true; done; sleep 1; rm -rf "$TMP"; }
trap cleanup EXIT
fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }
wait_for() { i=0; while [ "$i" -lt "$2" ]; do curl -fsS -o /dev/null "$1" 2>/dev/null && return 0; i=$((i + 1)); sleep 1; done; fail "not ready: $1"; }
if [ "${1:-}" != "--skip-build" ]; then echo "==> build-lite"; sh "$ROOT/scripts/build-lite.sh"; fi
need_file "$BE_JAR" "Run scripts/build-lite.sh first."; need_file "$FE_JAR" "Run scripts/build-lite.sh first."
cd "$ROOT"

MOCK_PORT="$(free_port)"
node scripts/mock-openai/server.mjs --port "$MOCK_PORT" >"$TMP/mock.log" 2>&1 & PIDS="$PIDS $!"
wait_for_mock() { i=0; while [ "$i" -lt 20 ]; do grep -q MOCK_OPENAI_LISTENING "$TMP/mock.log" && return 0; i=$((i + 1)); sleep 1; done; fail "mock not ready"; }
wait_for_mock

clean_env() { unset OPENAI_API_KEY JOURNAL_CLOUD_CONSENT JOURNAL_HTTP_PORT JOURNAL_HTTP_HOST BACKEND_URL JOURNAL_FRONTEND_PORT; }
lite_env() {
  export QUARKUS_PROFILE=lite-cloud JOURNAL_OPENAI_BASE_URL="http://127.0.0.1:$MOCK_PORT/v1"
  export JOURNAL_DB_URL="jdbc:h2:file:$TMP/h2/journal;DB_CLOSE_ON_EXIT=FALSE"
  export JOURNAL_AUDIO_FILESYSTEM_DIRECTORY="$TMP/audio" JOURNAL_TTS_CACHE_DIR="$TMP/tts-cache"
  export JOURNAL_STT_PROVIDER=openai JOURNAL_LLM_PROVIDER=openai JOURNAL_EMBEDDING_PROVIDER=openai JOURNAL_TTS_PROVIDER=openai
  export JOURNAL_VECTOR_PROVIDER=jdbc JOURNAL_AUDIO_PROVIDER=filesystem
}

echo "==> (a) refusals"
clean_env; lite_env; export OPENAI_API_KEY=sk-smoke-dummy JOURNAL_HTTP_PORT="$(free_port)"
# shellcheck disable=SC2086
if timeout 120 "$JAVA" $BE_OPTS -jar "$BE_JAR" >"$TMP/refuse1.log" 2>&1; then fail "backend started without consent"; fi
grep -q JOURNAL_CLOUD_CONSENT "$TMP/refuse1.log" || fail "no consent message"
clean_env; lite_env; export JOURNAL_CLOUD_CONSENT="$PHRASE" JOURNAL_HTTP_PORT="$(free_port)"
if timeout 120 "$JAVA" $BE_OPTS -jar "$BE_JAR" >"$TMP/refuse2.log" 2>&1; then fail "backend started without key"; fi
grep -q OPENAI_API_KEY "$TMP/refuse2.log" || fail "no key message"

echo "==> (b) start backend + frontend"
clean_env; lite_env; export OPENAI_API_KEY=sk-smoke-dummy JOURNAL_CLOUD_CONSENT="$PHRASE"
BE_PORT="$(free_port)"; FE_PORT="$(free_port)"; BE="http://127.0.0.1:$BE_PORT"; FE="http://127.0.0.1:$FE_PORT"
JOURNAL_HTTP_PORT="$BE_PORT" "$JAVA" $BE_OPTS -jar "$BE_JAR" >"$TMP/backend.log" 2>&1 & BE_PID=$!; PIDS="$PIDS $BE_PID"
wait_for "$BE/api/system/info" 240
BACKEND_URL="$BE" JOURNAL_FRONTEND_PORT="$FE_PORT" JOURNAL_HTTP_HOST=127.0.0.1 "$JAVA" $FE_OPTS -jar "$FE_JAR" >"$TMP/frontend.log" 2>&1 & FE_PID=$!; PIDS="$PIDS $FE_PID"
wait_for "$FE/" 240
INFO="$(curl -fsS "$BE/api/system/info")"
echo "$INFO" | grep -q '"profile":"lite-cloud"' || fail "profile not lite-cloud"
echo "$INFO" | grep -q '"cloudMode":true' || fail "cloudMode not true"
BROWSER="$(command -v chromium || command -v chromium-browser || command -v google-chrome || command -v microsoft-edge || true)"
if [ -n "$BROWSER" ]; then
  "$BROWSER" --headless=new --disable-gpu --no-sandbox "--user-data-dir=$TMP/browser" --virtual-time-budget=20000 --dump-dom "$FE/" 2>/dev/null | grep -Eq 'Cloud-Modus ist aktiv|Cloud mode is active' || fail "no cloud banner"
else echo "    SKIPPED: no browser, cloud banner not checked"; fi

echo "==> (c) AI features"
H='X-AI-Diary: 1'; J='Content-Type: application/json'
post() { curl -fsS -X POST -H "$H" -H "$J" -d "$2" "$BE$1"; }
ENTRY="$(post /api/entries '{"text":"Heute war ein langer Tag im Buero und ich habe viel ueber meine Arbeit nachgedacht. Ich habe mir vorgenommen, morgen frueher anzufangen und die wichtigste Aufgabe zuerst zu erledigen.","language":"DE","source":"TEXT"}')"
EID="$(echo "$ENTRY" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
curl -fsS -X POST -H "$H" "$BE/api/entries/$EID/finish" | grep -q FINISHED || fail "finish"
CONV="$(post /api/conversations "{\"entryId\":$EID}")"; CID="$(echo "$CONV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
post "/api/conversations/$CID/answers" '{"text":"Ich moechte morgen frueher anfangen und mehr Pausen machen.","voice":false}' | grep -q FOLLOW_UP_QUESTION || fail "coach reply"
post /api/memory/extractions "{\"sourceType\":\"ENTRY\",\"sourceId\":$EID}" | grep -q '"prompt"' || fail "memory extraction"
curl -fsS -X POST -H "$H" "$BE/api/gym/exercises" | grep -q '"personalized":true' || fail "gym not personalized"
YEAR="$(date +%G)"; WEEK="$(date +%V | sed 's/^0//')"
post /api/weekly-reports "{\"isoYear\":$YEAR,\"isoWeek\":$WEEK}" | grep -q recurringThemes || fail "weekly report"

echo "==> (d) transcribe and tts"
python3 -c 'import struct,sys;d=b"\0"*1600;sys.stdout.buffer.write(b"RIFF"+struct.pack("<I",36+len(d))+b"WAVEfmt "+struct.pack("<IHHIIHH",16,1,1,8000,16000,2,16)+b"data"+struct.pack("<I",len(d))+d)' >"$TMP/t.wav"
curl -fsS -X POST -H "$H" -H 'Content-Type: audio/wav' --data-binary "@$TMP/t.wav" "$BE/api/transcribe?language=DE" | grep -q 'Das ist ein Test der Spracherkennung.' || fail "transcribe"
curl -fsS -X POST -H "$H" -H "$J" -d '{"text":"Hallo Welt.","language":"DE"}' -o "$TMP/tts.bin" "$BE/api/tts"
[ "$(wc -c <"$TMP/tts.bin")" -gt 100 ] || fail "tts"

echo "==> (e) export and delete-all"
curl -fsS -o "$TMP/export.zip" "$BE/api/data/export"
unzip -l "$TMP/export.zip" | grep -q entries.json || fail "no entries.json"
post /api/data/delete-all '{"confirmation":"DELETE EVERYTHING"}' >/dev/null
curl -fsS "$BE/api/entries" | grep -q '"total":0' || fail "journal not empty"

echo "==> (f) RSS (Linux VmRSS)"
for p in "$BE_PID:backend" "$FE_PID:frontend"; do grep VmRSS "/proc/${p%%:*}/status" 2>/dev/null | sed "s/^/    ${p##*:} /" || true; done
echo "==> smoke-lite OK"
