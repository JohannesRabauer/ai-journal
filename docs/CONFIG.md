# Configuration reference

Every key can be set as an environment variable: upper-case, `.` and `-` become `_` (`journal.stt.provider` ->
`JOURNAL_STT_PROVIDER`). Docker compose, `scripts/run-*` and the systemd units read `.env` / `/etc/ai-diary/backend.env`. There is one process (the backend, which also serves the web pages, D-033), so one set of settings.
Template with all variables: [`.env.example`](../.env.example). Keys marked (W1a)/(W2b)... belong to a feature that
another worker implements; names below are the agreed contract, check `backend/src/main/resources/application.properties`
for the final defaults.

## Profiles

| Profile | `journal.profile` | Providers | DB | Start |
|---|---|---|---|---|
| A: docker compose | `full` | Ollama, Whisper, Piper (local) | embedded H2 file, `jdbc` vector store | `docker compose --profile cpu up --build -d` |
| B: plain Java | `full` | same, services run by you | embedded H2 file, `jdbc` vector store | `scripts/run-backend.*` |
| C: `lite-cloud` | `lite-cloud` | OpenAI only | H2 file, in-JVM vectors | `scripts/run-lite.*` or systemd (docs/PI.md) |

One build serves every profile (D-032). `lite-cloud` is selected purely at runtime and needs
`QUARKUS_PROFILE=lite-cloud`, `OPENAI_API_KEY` and `JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`.
Startup fails without them. It cannot be activated by accident in A/B.

## Providers per capability

| Key (env) | Allowed values | Default A/B | Default `lite-cloud` |
|---|---|---|---|
| `journal.stt.provider` (`JOURNAL_STT_PROVIDER`) | `whisper`, `openai`*, `fake` | `whisper` | `openai` |
| `journal.llm.provider` | `ollama`, `openai`*, `fake` | `ollama` | `openai` |
| `journal.embedding.provider` | `ollama`, `openai`*, `fake` | `ollama` | `openai` |
| `journal.vector.provider` | `jdbc` (cosine similarity in Java), `memory` | `jdbc` | `jdbc` |
| `journal.audio.provider` | `filesystem`, `memory` | `filesystem` | `filesystem` |
| `journal.tts.provider` | `piper`, `openai`*, `none`, `fake` | `none` (set `piper` with the `tts` compose profile) | `openai` |
| `journal.scheduler.provider` | `fake` (W3a adds the real one) | `fake` | `fake` |

\* `openai` sends data off the device and is only accepted with `journal.profile=lite-cloud`.
`fake`/`memory` are for tests and development. Compose defaults come from `docker-compose.yml`; without the `tts`
compose profile keep `JOURNAL_TTS_PROVIDER=none` (the default, D-021; the coach shows text). With `--profile tts` set
`JOURNAL_TTS_PROVIDER=piper` in `.env`. See "Text to speech" below.

## Core keys (backend)

| Key | Env | Default | Meaning |
|---|---|---|---|
| `journal.profile` | `JOURNAL_PROFILE` | `full` | `full` or `lite-cloud` |
| `journal.cloud.consent` | `JOURNAL_CLOUD_CONSENT` | empty | Must equal `I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE` in lite-cloud |
| `journal.openai.api-key` | `OPENAI_API_KEY` | empty | lite-cloud only |
| `journal.openai.base-url` | `JOURNAL_OPENAI_BASE_URL` | `https://api.openai.com/v1` | |
| `quarkus.http.host` | `JOURNAL_HTTP_HOST` | `127.0.0.1` | Containers set `0.0.0.0` and publish on `127.0.0.1` |
| `quarkus.http.port` | `JOURNAL_HTTP_PORT` | `8080` | |
| `quarkus.datasource.jdbc.url` | `JOURNAL_DB_URL` | `jdbc:h2:file:./data/journal;DB_CLOSE_ON_EXIT=FALSE` | Embedded H2 file `data/journal.mv.db`; compose uses `jdbc:h2:file:/data/db/journal;...` |
| `quarkus.datasource.username` | `JOURNAL_DB_USER` | `journal` | |

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
| `journal.tts.piper.timeout` / `.voice-de` / `.voice-en` | | `30s` / `de_DE-thorsten-medium` / `en_US-lessac-medium` | W2b |
| `journal.tts.openai.model` / `.voice` / `.format` / `.timeout` | | `gpt-4o-mini-tts` / `alloy` / `mp3` (or `wav`) / `30s` | W2b (lite-cloud) |
| `journal.tts.cache.directory` / `.max-entries` | `JOURNAL_TTS_CACHE_DIR` / `JOURNAL_TTS_CACHE_MAX_ENTRIES` | `./data/tts-cache` (compose: `/data/tts-cache`) / `500` | W2b |

The Ollama models named above must be pulled into the Ollama instance. Compose does that automatically on the first
start (`OLLAMA_MODELS_TO_PULL` in `.env`, default `qwen3-embedding:0.6b qwen3:1.7b`). If you change a model key, change
`OLLAMA_MODELS_TO_PULL` too. A changed embedding model never mixes vector spaces (vectors carry their `modelId`, D-012).

## Text to speech (W2b)

`POST /api/tts` `{text, language, voice}` answers audio (`audio/wav` for Piper, `audio/mpeg` for OpenAI by default) or
**204 No Content** whenever speech is unavailable (provider `none`, Piper down, OpenAI error): clients then show the text only.
Text must be 1..1000 characters (else 400). `language` AUTO is guessed (German markers, otherwise English). The voice is the
request's `voice`, else the voice chosen in the settings (`ttsVoiceDe`/`ttsVoiceEn`) if the active provider lists it,
else the provider default (`journal.tts.piper.voice-de/-en`, `journal.tts.openai.voice`). `GET /api/tts/voices` lists the
provider's voices (Piper: installed models by `de_`/`en_` prefix; empty when Piper is unreachable).

Cache: audio is stored as files in `journal.tts.cache.directory` under `sha256(provider, voice, language, text)`, at most
`journal.tts.cache.max-entries` files (least recently used evicted). A cache hit never calls the provider. The cache holds
spoken coach text, so "delete all" (SPEC section 10) clears it; it is not part of the export (derived data).
The OpenAI provider is lite-cloud only; the format defaults to mp3 (about 10x smaller than wav, which keeps the cache small).
Logs contain the provider and failure kind only, never the text.

## Web pages (served by the backend, D-033)

The pages are Qute templates rendered by the backend; there is no separate frontend process and no `BACKEND_URL`. The
browser opens `http://127.0.0.1:8080` (the backend's `JOURNAL_HTTP_PORT`). Static files (`/css`, `/js`, `/icons`) are
served with `max-age=0` (`quarkus.http.static-resources.max-age`) so an update never runs with stale assets.

## Request guard (CSRF, DNS rebinding) and exposing services

The backend protects the unauthenticated API and the pages (docs/PRIVACY.md, "Protection of the local API"):

| Key | Env | Default | Meaning |
|---|---|---|---|
| `journal.http.allowed-hosts` | `JOURNAL_ALLOWED_HOSTS` | empty | Comma list of extra `Host` names accepted besides `localhost`, `127.0.0.1`, `[::1]` (and `backend`). Needed for the Pi/LAN opt-in |
| `journal.http.allowed-origins` | none | empty | Extra browser origins allowed to call the API. Leave empty: the backend's own origin is accepted automatically (see below) |
| `quarkus.http.host` | `JOURNAL_HTTP_HOST` | `127.0.0.1` | Bind address. `0.0.0.0` only for the LAN opt-in |

### LAN access (D-033)
The browser talks to the backend directly, so the LAN opt-in is a backend setting. Off by default: the backend binds `127.0.0.1` and accepts only the loopback
names. Anyone on the LAN can then read the journal (no login); never expose it to the internet.

```
JOURNAL_HTTP_HOST=0.0.0.0                                              # bind on all interfaces
JOURNAL_ALLOWED_HOSTS=raspberrypi,raspberrypi.local,192.168.1.50       # the names/IPs you type into the browser
```

Without `JOURNAL_ALLOWED_HOSTS` the Host check answers `421` for every other name. Set both in `/etc/ai-diary/backend.env`
on the Pi (`deploy/backend.env.example`) or in `.env` for the scripts and compose. The port is `JOURNAL_HTTP_PORT` (8080).

What is accepted automatically, with no further setting:
- **Own origin.** A request whose `Origin` is `http(s)://` plus the very `Host` the browser used (and that host is
  allowed) passes the origin check, on any port and for every allowed LAN name: the pages the backend serves can call
  `/api` without configuration. Every other origin (another site, another port, another host name) gets `403`.
- **Writes.** Every non-GET/HEAD/OPTIONS request needs `X-AI-Diary: 1` (what `js/api.js` sends). The one alternative
  is a plain HTML form post (`application/x-www-form-urlencoded`, outside `/api`) with the per-session token:
  cookie `diary-csrf` (`HttpOnly`, `SameSite=Strict`, no `Secure` on plain HTTP, set by the first page load) and the
  hidden field `_csrf` (`{#csrf /}` in templates), which the route verifies with `Csrf.require`. The token secret is
  per process: after a restart open forms must be reloaded.
- **Time zone.** `js/tz.js` stores the browser zone in the cookie `diary-tz` (no journal content); "Today" and
  "Yesterday" follow it, the server zone is the fallback.
- **Headers.** HTML: CSP without inline script/style, `nosniff`, `no-referrer`, `frame-ancestors 'none'`, `no-store`.
  Every `/api` response: `no-store` and `nosniff`. Static files under `/css`, `/js`, `/icons` may be cached.
- **OpenAPI.** `/q/openapi` and the Swagger UI exist only in the `%dev` and `%test` profiles; prod and lite-cloud have
  neither (`quarkus.smallrye-openapi.enable=false`).

Every non-GET request to the backend needs the header `X-AI-Diary: 1`:

```
curl -X POST -H "X-AI-Diary: 1" -H "Content-Type: application/json" -d '{"text":"hello"}' http://127.0.0.1:8080/api/entries
Invoke-RestMethod -Method Post -Headers @{ 'X-AI-Diary' = '1' } -ContentType 'application/json' -Body '{"text":"hello"}' -Uri http://127.0.0.1:8080/api/entries
```

In compose, Ollama, Whisper and Piper publish no ports. To reach them from the host for debugging, create a
`docker-compose.override.yml` (not committed; compose merges it automatically):

```yaml
services:
  ollama-cpu:        # or ollama-gpu
    ports: [ "127.0.0.1:11434:11434" ]
  whisper-cpu:       # or whisper-gpu
    ports: [ "127.0.0.1:8000:8000" ]
  piper:
    ports: [ "127.0.0.1:5000:5000" ]
```

Profile B (plain Java) runs these services yourself on the default local ports, as before.

## Compose and script variables (not backend keys)

| Variable | Used by | Meaning |
|---|---|---|
| `JOURNAL_ALLOWED_HOSTS` | compose, systemd, scripts | Extra accepted `Host` names (comma list), see above and docs/PI.md |
| `WHISPER_ALLOW_ONLINE` | whisper container | `1` keeps the Hugging Face client online after the model is cached (default: offline) |
| `JOURNAL_DB_DATA_DIR` | compose | Host directory of the H2 database file `journal.mv.db` (default `./data/db`) |
| `JOURNAL_UID`, `JOURNAL_GID` | compose (Linux) | User the backend/piper containers run as (default 1001:1001) |
| `OLLAMA_MODELS_TO_PULL` | compose | Space separated model list pulled on first start |
| `WHISPER_MODEL` | compose | Hugging Face id of the faster-whisper model |
| `PIPER_VOICES` | compose | Voices downloaded on first start (default `de_DE-thorsten-medium en_US-lessac-medium`) |
| `LITE_BACKEND_JVM_OPTS` | `scripts/run-lite.*` | Override the tuned JVM flags |

## Whisper service (`whisper/`)

Pinned upstream image `ghcr.io/speaches-ai/speaches:0.8.3-cpu` / `-cuda` (faster-whisper behind an OpenAI-compatible API) plus a
first-start model download. Default model **`Systran/faster-whisper-small`** (multilingual, de + en, about 500 MB, good
accuracy on CPU; use `Systran/faster-whisper-medium` or `large-v3` on a GPU for better quality via `WHISPER_MODEL`).
The model is cached in `./data/whisper` and downloaded from huggingface.co once; afterwards the container neither calls the
download endpoint nor lets the Hugging Face client go online (`HF_HUB_OFFLINE=1`), so it makes no outbound connection. The service reports healthy only when the model is cached. Logging is set to `info` (no request details).

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
