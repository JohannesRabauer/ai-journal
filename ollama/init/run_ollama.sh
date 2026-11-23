#!/bin/sh
# Starts Ollama and pulls the configured models once (cached in the ./data/ollama volume).
# Models are pulled from the Ollama registry on first start only; afterwards everything is offline.
# OLLAMA_MODELS_TO_PULL: space separated list, default below. Nothing is logged about journal content.
set -u

MODELS="${OLLAMA_MODELS_TO_PULL:-qwen3-embedding:0.6b qwen3:1.7b}"

ollama serve &
SERVER_PID=$!

echo "Waiting for the Ollama server..."
until ollama list >/dev/null 2>&1; do
  sleep 1
done
echo "Ollama is ready"

for m in $MODELS; do
  echo "Pulling model $m (skipped when already present)"
  ollama pull "$m" || echo "WARNING: could not pull $m (offline?). Pull it later with: docker compose exec <ollama service> ollama pull $m"
done
echo "Models ready: $MODELS"

wait "$SERVER_PID"
