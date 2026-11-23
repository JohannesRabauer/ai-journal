#!/bin/sh
# Starts Speaches, then downloads WHISPER_MODEL into the cache volume if it is not there yet
# (needs internet once, to huggingface.co; afterwards fully offline). Idempotent.
set -u
MODEL="${WHISPER_MODEL:-Systran/faster-whisper-small}"

uvicorn --factory speaches.main:create_app &
SERVER_PID=$!

(
  until curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; do sleep 1; done
  echo "Ensuring model $MODEL is available (first start downloads it)"
  if curl -fsS -X POST "http://127.0.0.1:8000/v1/models/$MODEL" >/dev/null; then
    echo "Model ready: $MODEL"
  else
    echo "WARNING: could not download $MODEL (offline?). Transcription fails until it is cached." >&2
  fi
) &

trap 'kill "$SERVER_PID" 2>/dev/null' TERM INT
wait "$SERVER_PID"
