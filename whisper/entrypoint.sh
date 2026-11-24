#!/bin/sh
# Starts Speaches, then downloads WHISPER_MODEL into the cache volume if it is not there yet
# (needs internet once, to huggingface.co). Once the model directory exists nothing is downloaded and the
# Hugging Face client is put into offline mode, so the container makes no network calls at all. Idempotent.
# Set WHISPER_ALLOW_ONLINE=1 to keep the client online (e.g. to fetch another model by hand).
set -u
MODEL="${WHISPER_MODEL:-Systran/faster-whisper-small}"
HUB="${HF_HUB_CACHE:-/home/ubuntu/.cache/huggingface/hub}"
MODEL_DIR="$HUB/models--$(printf '%s' "$MODEL" | sed 's#/#--#g')"

if [ -d "$MODEL_DIR" ]; then
  CACHED=1
  if [ "${WHISPER_ALLOW_ONLINE:-0}" != "1" ]; then
    export HF_HUB_OFFLINE=1
  fi
else
  CACHED=0
fi

uvicorn --factory speaches.main:create_app &
SERVER_PID=$!

(
  until curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; do sleep 1; done
  if [ "$CACHED" = "1" ]; then
    echo "Model $MODEL is cached, not contacting Hugging Face"
  else
    echo "Downloading model $MODEL (first start only)"
    if curl -fsS -X POST "http://127.0.0.1:8000/v1/models/$MODEL" >/dev/null; then
      echo "Model ready: $MODEL (restart the container to switch the Hugging Face client to offline mode)"
    else
      echo "WARNING: could not download $MODEL (offline?). Transcription fails until it is cached." >&2
    fi
  fi
) &

trap 'kill "$SERVER_PID" 2>/dev/null' TERM INT
wait "$SERVER_PID"
