#!/bin/sh
# Downloads missing voices into the volume on first start (needs internet once), then starts the service.
set -eu
VOICES_DIR="${PIPER_VOICES_DIR:-/voices}"
for v in ${PIPER_VOICES:-de_DE-thorsten-medium en_US-lessac-medium}; do
  if [ ! -f "$VOICES_DIR/$v.onnx" ]; then
    echo "Downloading voice $v (first start only)"
    python -m piper.download_voices --download-dir "$VOICES_DIR" "$v"
  fi
done
exec python /app/server.py
