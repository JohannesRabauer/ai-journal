#!/bin/sh
# Starts Ollama and pulls the configured models once (cached in the ./data/ollama volume).
# Models are pulled from the Ollama registry only while they are missing; once cached nothing leaves the machine.
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

# `ollama pull` contacts the registry even for a model that is already present, so only pull what is missing.
# After the first start no network access happens at all.
for m in $MODELS; do
  case "$m" in
    *:*) wanted="$m" ;;
    *) wanted="$m:latest" ;;
  esac
  if ollama list | awk 'NR > 1 { print $1 }' | grep -qxF "$wanted"; then
    echo "Model $m is present, not contacting the registry"
  else
    echo "Pulling model $m (first start only; needs internet once)"
    ollama pull "$m" || echo "WARNING: could not pull $m (offline?). Pull it later with: docker compose exec <ollama service> ollama pull $m"
  fi
done
echo "Models ready: $MODELS"

wait "$SERVER_PID"
