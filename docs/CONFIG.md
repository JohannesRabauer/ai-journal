# Configuration reference

Every key can be set as an environment variable: upper-case, `.` and `-` become `_` (`journal.stt.provider` ->
`JOURNAL_STT_PROVIDER`). Docker compose, `scripts/run-*` and the systemd units read `.env` / `/etc/ai-diary/env`.
Template with all variables: [`.env.example`](../.env.example). Keys marked (W1a)/(W2b)... belong to a feature that
another worker implements; names below are the agreed contract, check `backend/src/main/resources/application.properties`
for the final defaults.

## Profiles

| Profile | `journal.profile` | Providers | DB | Start |
|---|---|---|---|---|
| A: docker compose | `full` | Ollama, Whisper, Piper (local) | PostgreSQL + pgvector | `docker compose --profile cpu up --build -d` |
| B: plain Java | `full` | same, services run by you | PostgreSQL | `scripts/run-backend.*`, `scripts/run-frontend.*` |
| C: `lite-cloud` | `lite-cloud` | OpenAI only | H2 file, in-JVM vectors | `scripts/run-lite.*` or systemd (docs/PI.md) |

`lite-cloud` is a separate build (`scripts/build-lite.*`, datasource kind is a build-time decision, D-007) and needs
`QUARKUS_PROFILE=lite-cloud`, `OPENAI_API_KEY` and `JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`.
Startup fails without them. It cannot be activated by accident in A/B.

## Providers per capability

| Key (env) | Allowed values | Default A/B | Default `lite-cloud` |
|---|---|---|---|
| `journal.stt.provider` (`JOURNAL_STT_PROVIDER`) | `whisper`, `openai`*, `fake` | `whisper` | `openai` |
| `journal.llm.provider` | `ollama`, `openai`*, `fake` | `ollama` | `openai` |
| `journal.embedding.provider` | `ollama`, `openai`*, `fake` | `ollama` | `openai` |
| `journal.vector.provider` | `pgvector` (PostgreSQL only), `jdbc` (H2 or PostgreSQL), `memory` | `pgvector` | `jdbc` |
| `journal.audio.provider` | `filesystem`, `memory` | `filesystem` | `filesystem` |
| `journal.tts.provider` | `piper`, `openai`*, `none`, `fake` | `piper` | `openai` |
| `journal.scheduler.provider` | `fake` (W3a adds the real one) | `fake` | `fake` |

\* `openai` sends data off the device and is only accepted with `journal.profile=lite-cloud`.
`fake`/`memory` are for tests and development. Compose defaults come from `docker-compose.yml`; without the `tts`
compose profile set `JOURNAL_TTS_PROVIDER=none` in `.env` (speech output is then simply unavailable, the coach shows
text).

## Core keys (backend)

| Key | Env | Default | Meaning |
|---|---|---|---|
| `journal.profile` | `JOURNAL_PROFILE` | `full` | `full` or `lite-cloud` |
| `journal.cloud.consent` | `JOURNAL_CLOUD_CONSENT` | empty | Must equal `I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE` in lite-cloud |
| `journal.openai.api-key` | `OPENAI_API_KEY` | empty | lite-cloud only |
| `journal.openai.base-url` | `JOURNAL_OPENAI_BASE_URL` | `https://api.openai.com/v1` | |
| `quarkus.http.host` | `JOURNAL_HTTP_HOST` | `127.0.0.1` | Containers set `0.0.0.0` and publish on `127.0.0.1` |
| `quarkus.http.port` | `JOURNAL_HTTP_PORT` | `8080` | |
| `quarkus.datasource.jdbc.url` | `JOURNAL_DB_URL` | `jdbc:postgresql://localhost:15432/ai-diary` (lite: `jdbc:h2:file:./data/journal;DB_CLOSE_ON_EXIT=FALSE`) | |
| `quarkus.datasource.username` | `JOURNAL_DB_USER` | `ai-diary` (lite: `journal`) | |
| `quarkus.datasource.password` | `JOURNAL_DB_PASSWORD` | empty | Required for PostgreSQL; startup validation reports it |

## Provider keys

| Key | Env | Default | Owner |
|---|---|---|---|
| `journal.stt.whisper.base-url` | `JOURNAL_STT_WHISPER_BASE_URL` | `http://localhost:8000` (compose: `http://whisper:8000`) | W1a |
| `journal.stt.whisper.model` | `JOURNAL_STT_WHISPER_MODEL` | `Systran/faster-whisper-small` | W1a |
| `journal.llm.ollama.base-url` | `JOURNAL_LLM_OLLAMA_BASE_URL` | `http://localhost:11434` (compose: `http://ollama:11434`) | W1e |
| `journal.llm.ollama.model` / `.timeout` / `.temperature` | `JOURNAL_LLM_OLLAMA_MODEL` ... | `qwen3:1.7b` / `5m` | W1e |
| `journal.embedding.ollama.base-url` | `JOURNAL_EMBEDDING_OLLAMA_BASE_URL` | `http://localhost:11434` | W1e |
| `journal.embedding.ollama.model` / `.timeout` | `JOURNAL_EMBEDDING_OLLAMA_MODEL` ... | `qwen3-embedding:0.6b` / `2m` | W1e |
| `journal.llm.openai.model` / `.timeout` | | `gpt-4.1-mini` | W1e (lite-cloud) |
| `journal.embedding.openai.model` / `.timeout` | | `text-embedding-3-small` | W1e (lite-cloud) |
| `journal.index.backfill.enabled` / `.initial-delay` / `.interval` / `.pause` | | see application.properties | W1e |
| `journal.audio.filesystem.directory` | `JOURNAL_AUDIO_FILESYSTEM_DIRECTORY` | `./data/audio` (compose: `/data/audio`) | W1a |
| `journal.tts.piper.base-url` | `JOURNAL_TTS_PIPER_BASE_URL` | `http://localhost:5000` (compose: `http://piper:5000`) | W2b |

The Ollama models named above must be pulled into the Ollama instance. Compose does that automatically on the first
start (`OLLAMA_MODELS_TO_PULL` in `.env`, default `qwen3-embedding:0.6b qwen3:1.7b`). If you change a model key, change
`OLLAMA_MODELS_TO_PULL` too. A changed embedding model never mixes vector spaces (vectors carry their `modelId`, D-012).

## Frontend

| Key | Env | Default |
|---|---|---|
| `backend.url` | `BACKEND_URL` | `http://127.0.0.1:8080` |
| `server.address` | `JOURNAL_HTTP_HOST` | `127.0.0.1` |
| `server.port` | `JOURNAL_FRONTEND_PORT` | `8081` |

## Compose and script variables (not backend keys)

| Variable | Used by | Meaning |
|---|---|---|
| `JOURNAL_DB_PASSWORD` | compose, scripts | **Required.** PostgreSQL password and backend DB password |
| `JOURNAL_DB_DATA_DIR` | compose | Host directory of the PostgreSQL data (default `./db/data`) |
| `JOURNAL_UID`, `JOURNAL_GID` | compose (Linux) | User the backend/piper containers run as (default 1001:1001) |
| `OLLAMA_MODELS_TO_PULL` | compose | Space separated model list pulled on first start |
| `WHISPER_MODEL` | compose | Hugging Face id of the faster-whisper model |
| `PIPER_VOICES` | compose | Voices downloaded on first start (default `de_DE-thorsten-medium en_US-lessac-medium`) |
| `LITE_BACKEND_JVM_OPTS`, `LITE_FRONTEND_JVM_OPTS` | `scripts/run-lite.*` | Override the tuned JVM flags |

## Whisper service (`whisper/`)

Pinned upstream image `ghcr.io/speaches-ai/speaches:0.8.3-cpu` / `-cuda` (faster-whisper behind an OpenAI-compatible API) plus a
first-start model download. Default model **`Systran/faster-whisper-small`** (multilingual, de + en, about 500 MB, good
accuracy on CPU; use `Systran/faster-whisper-medium` or `large-v3` on a GPU for better quality via `WHISPER_MODEL`).
The model is cached in `./data/whisper` and downloaded from huggingface.co once; afterwards no outbound connection is
needed. The service reports healthy only when the model is cached. Logging is set to `info` (no request details).

```
POST /v1/audio/transcriptions   multipart: file, model, language (de|en, optional), response_format=verbose_json
-> {"task":"transcribe","language":"de","duration":1.69,"text":"...","segments":[...]}
GET  /health, GET /v1/models
```

Verified: a German Piper sample is transcribed correctly with `language=de`.

## Piper service (`piper/`)

Own minimal Flask/waitress service on the pinned `piper-tts==1.8.0` (python 3.12). It never logs or keeps request text
(the upstream `piper.http_server` exposes the last utterance on `GET /info`). Voices (`de_DE-thorsten-medium`,
`en_US-lessac-medium`) are downloaded from Hugging Face on the first start into `./data/piper`, then offline. This is the
contract for the TTS client (W2b):

```
POST /synthesize
  Content-Type: application/json
  {"text": "Wie war dein Tag?", "voice": "de_DE-thorsten-medium", "length_scale": 1.0}
  text          required, 1..5000 chars
  voice         optional, installed voice name (default en_US-lessac-medium); unknown voice -> 400 unknown_voice
  length_scale  optional 0.5..2.0 (larger = slower speech), default from the voice
-> 200  Content-Type: audio/wav   (PCM 16 bit mono, sample rate of the voice, 22050 Hz for the default voices)
-> 400  {"error": "empty_text|text_too_long|unknown_voice|invalid_json|invalid_length_scale", "message": "..."}

GET /voices  -> {"voices": ["de_DE-thorsten-medium", "en_US-lessac-medium"], "default": "en_US-lessac-medium"}
GET /health  -> {"status": "ok", "voices": 2}
```

Suggested mapping: entry language `de` -> `de_DE-thorsten-medium`, `en` -> `en_US-lessac-medium`.
Verified with the German voice (HTTP 200, `audio/wav`).
