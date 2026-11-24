# AI Diary

A local-first, privacy-focused voice and text journal. You write or speak; entries are saved first, then transcribed
(voice) and indexed. Nothing is analysed while you write. Afterwards you can reflect with a coach that asks questions,
turn learnings into memory items with spaced review, do thinking exercises and read weekly reflections.

No telemetry, no external fonts or CDNs. In the full profiles nothing leaves your machine except one-time model
downloads. Details: [docs/PRIVACY.md](docs/PRIVACY.md). Specification: [docs/SPEC.md](docs/SPEC.md).

Stack: Quarkus 3.31 (one backend process: REST API plus server-rendered Qute pages with small browser modules), embedded H2
file database, Ollama, faster-whisper, Piper, Java 25. There is no separate frontend process (D-033).

## Three ways to run it

| Profile | For | AI | Data leaves the device? |
|---|---|---|---|
| **A** docker compose | a PC or server with Docker | local: Ollama, Whisper, Piper | no |
| **B** plain Java | development, or your own services | local, run by you | no |
| **C** `lite-cloud` | Raspberry Pi 3 (1 GB) | OpenAI API | **yes** (text and audio) |

Optional for all: copy `.env.example` to `.env` to change settings (there is no database password; the database is an embedded H2 file). Never commit `.env`.
Every setting: [docs/CONFIG.md](docs/CONFIG.md).

### Profile A: docker compose

Needs Docker with Compose, about 8 GB RAM, about 8 GB disk for models. Choose **one** of `gpu` (NVIDIA GPU with
the container toolkit) or `cpu`; add `tts` for spoken questions and set `JOURNAL_TTS_PROVIDER=piper` in `.env` (default is `none`).

Windows PowerShell:
```powershell
Copy-Item .env.example .env          # optional: edit .env
docker compose --profile cpu --profile tts up --build -d
# GPU instead:  docker compose --profile gpu --profile tts up --build -d
# without speech output (keep the default JOURNAL_TTS_PROVIDER=none):  docker compose --profile cpu up --build -d
```
sh (Linux/macOS):
```sh
cp .env.example .env                 # optional: edit .env
mkdir -p data/audio data/tts-cache data/whisper data/piper data/ollama
docker compose --profile cpu --profile tts up --build -d
```
Open http://127.0.0.1:8080. The first start downloads models (Ollama registry, Hugging Face: Whisper small and two
Piper voices); the Whisper service becomes healthy once its model is cached. Check with `docker compose ps`. Stop with
`docker compose --profile cpu --profile tts down`. The backend port (API and web pages) is published on `127.0.0.1` only (the database is embedded H2 in the backend, no port); Ollama, Whisper and Piper publish none (docs/CONFIG.md shows how to expose them for debugging). On Linux set
`JOURNAL_UID`/`JOURNAL_GID` in `.env` to your user if it is not 1001.

Data lives in `data/db/journal.mv.db` (compose) or `data/journal.mv.db` (plain Java). Backup: stop the backend and copy that folder.

#### Importing data from the old PostgreSQL version
Earlier versions used PostgreSQL + pgvector. To keep that data, run while the backend is stopped:
`pwsh scripts/import-legacy-postgres.ps1 -DataDir db/data_personal` (sh: `sh scripts/import-legacy-postgres.sh <datadir>`).
It works on a COPY of the old data directory with a temporary container, loads the legacy tables `documents` and `chat_history`
into the H2 file database and stops nothing else.

### Profile B: plain Java

The database is embedded (H2 file `data/journal.mv.db`), no Docker needed for it. Run Ollama
(`ollama pull qwen3:1.7b qwen3-embedding:0.6b`), a Whisper service and optionally Piper yourself, then set their
endpoints in `.env` (defaults: `localhost:11434`, `:8000`, `:5000`). Needs JDK 25 (`JAVA_HOME`).

Windows PowerShell:
```powershell
scripts\run-backend.ps1       # builds with mvnw on first run (-Build to force)
```
sh:
```sh
sh scripts/run-backend.sh     # --build to force
```
Open http://127.0.0.1:8080 (the one process serves the pages and the API).

### Profile C: lite-cloud (Raspberry Pi 3)

**Entry text and audio are sent to OpenAI.** It only starts with `OPENAI_API_KEY` and
`JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`. Build on a PC, run on the Pi with systemd:
[docs/PI.md](docs/PI.md) (Raspberry Pi OS Lite 64-bit only). To try it on a PC:

```powershell
scripts\build-lite.ps1
# set OPENAI_API_KEY and JOURNAL_CLOUD_CONSENT in .env, then:
scripts\run-lite.ps1
```
```sh
sh scripts/build-lite.sh && sh scripts/run-lite.sh
```

## Privacy in short
- Profiles A and B: all data and all AI stay on your machine; ports bind to `127.0.0.1`; logs never contain journal content.
- Export everything as a zip (Settings, or `GET /api/data/export?includeAudio=true`) and delete everything with a
  typed confirmation (Settings, or `POST /api/data/delete-all`). Direct API writes (curl, scripts) must send the header
  `X-AI-Diary: 1`, for example `curl -X POST -H "X-AI-Diary: 1" -H "Content-Type: application/json" -d '{"text":"hi"}' http://127.0.0.1:8080/api/entries`
  (CSRF protection, docs/PRIVACY.md).
- Profile C relaxes this: see the caveat in [docs/PRIVACY.md](docs/PRIVACY.md).

## Development
- `scripts\verify.ps1` / `sh scripts/verify.sh`: build and all tests, Java and the node tests of the browser modules (no network after dependencies are cached, no Docker).
- Layout: `api/` (shared REST records), `backend/` (Quarkus; pages in `backend/src/main/java/.../web/ui`, templates in `src/main/resources/templates`, CSS and JS in `src/main/resources/META-INF/resources`).
- `scripts\smoke-compose.ps1` / `sh scripts/smoke-compose.sh`: opt-in Docker smoke test (builds the image, starts
  the backend with fake providers in a throwaway project, checks the HTML pages, then removes everything).
- `scripts\smoke-plain.ps1` / `sh scripts/smoke-plain.sh`: opt-in smoke test of profile B (no Docker, throwaway H2 file, the
  backend via `run-backend` with fake providers; HTML pages, entry, export, delete-all).
- `scripts\smoke-lite.ps1` / `sh scripts/smoke-lite.sh`: opt-in smoke test of profile C against a local mock of the OpenAI
  API (`scripts/mock-openai`, Node 18+, nothing leaves your machine): consent/key refusal, cloud banner, coach, memory,
  gym, weekly report, transcribe, TTS, export, delete-all, memory of the JVM. The `.sh` mirrors are untested.
- Docs: [PLAN](docs/PLAN.md), [DECISIONS](docs/DECISIONS.md), [CONVENTIONS](docs/CONVENTIONS.md),
  [CONFIG](docs/CONFIG.md), [PRIVACY](docs/PRIVACY.md), [PI](docs/PI.md).
