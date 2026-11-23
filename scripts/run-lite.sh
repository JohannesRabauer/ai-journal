#!/usr/bin/env sh
# Profile C (lite-cloud): runs backend + frontend with the tuned low-memory JVM flags (docs/PI.md).
# Entry text and audio are SENT TO OPENAI in this profile. It refuses to start without OPENAI_API_KEY and
# JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE. Run scripts/build-lite.sh first.
# On a Pi use the systemd units instead (deploy/systemd, docs/PI.md).
set -eu
. "$(dirname "$0")/run-common.sh"
import_dotenv

PHRASE="I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE"
bad=0
if [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "  - OPENAI_API_KEY is not set (OpenAI key used for chat, embeddings, transcription and speech)." >&2; bad=1
fi
if [ "${JOURNAL_CLOUD_CONSENT:-}" != "$PHRASE" ]; then
  echo "  - JOURNAL_CLOUD_CONSENT must be exactly $PHRASE." >&2; bad=1
fi
if [ "$bad" = 1 ]; then
  cat >&2 <<'MSG'

lite-cloud will not start.
What this means: in the lite-cloud profile your entry text and audio recordings are sent to OpenAI
for transcription, questions, embeddings and speech. This relaxes the local-only privacy guarantee
(docs/PRIVACY.md). Set both values in .env only if you accept that.
MSG
  exit 1
fi

BACKEND_JAR="$REPO_ROOT/backend/target/lite-app/quarkus-run.jar"
FRONTEND_JAR="$REPO_ROOT/frontend/target/app.jar"
need_file "$BACKEND_JAR" "Run scripts/build-lite.sh first."
need_file "$FRONTEND_JAR" "Run scripts/build-lite.sh first."

export QUARKUS_PROFILE=lite-cloud
set_default JOURNAL_STT_PROVIDER openai
set_default JOURNAL_LLM_PROVIDER openai
set_default JOURNAL_EMBEDDING_PROVIDER openai
set_default JOURNAL_TTS_PROVIDER openai
set_default JOURNAL_VECTOR_PROVIDER jdbc
set_default JOURNAL_AUDIO_PROVIDER filesystem
DATA="$REPO_ROOT/data/lite"
mkdir -p "$DATA/audio"
set_default JOURNAL_AUDIO_FILESYSTEM_DIRECTORY "$DATA/audio"
set_default JOURNAL_DB_URL "jdbc:h2:file:$DATA/journal;DB_CLOSE_ON_EXIT=FALSE"

COMMON="-XX:+UseSerialGC -Xss256k -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -Xms32m"
BE_OPTS="${LITE_BACKEND_JVM_OPTS:--Xmx128m -XX:MaxMetaspaceSize=96m $COMMON}"
FE_OPTS="${LITE_FRONTEND_JVM_OPTS:--Xmx160m -XX:MaxMetaspaceSize=128m $COMMON}"
JAVA="$(java_bin)"

echo "==> lite-cloud: data leaves this device (OpenAI). Starting backend, then frontend (Ctrl+C stops both)."
# shellcheck disable=SC2086
"$JAVA" $BE_OPTS -jar "$BACKEND_JAR" &
BE_PID=$!
trap 'kill "$BE_PID" 2>/dev/null || true' EXIT INT TERM
# shellcheck disable=SC2086
"$JAVA" $FE_OPTS -jar "$FRONTEND_JAR"
