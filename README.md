# AI Diary

A local-first, privacy-focused voice and text journal. You write or speak; entries are saved first, then transcribed
(voice) and indexed. Nothing is analysed while you write. Afterwards you can reflect with a coach that asks questions,
turn learnings into memory items with spaced review, do thinking exercises and read weekly reflections.

No telemetry, no external fonts or CDNs. In the full profiles nothing leaves your machine except one-time model
downloads. Details: [docs/PRIVACY.md](docs/PRIVACY.md). Specification: [docs/SPEC.md](docs/SPEC.md).

Stack: Quarkus 3.29 (backend), Vaadin 24 on Spring Boot (frontend), PostgreSQL + pgvector, Ollama, faster-whisper,
Piper, Java 25.

## Three ways to run it

| Profile | For | AI | Data leaves the device? |
|---|---|---|---|
| **A** docker compose | a PC or server with Docker | local: Ollama, Whisper, Piper | no |
| **B** plain Java | development, or your own services | local, run by you | no |
| **C** `lite-cloud` | Raspberry Pi 3 (1 GB) | OpenAI API | **yes** (text and audio) |

Prerequisite for all: copy `.env.example` to `.env` and set `JOURNAL_DB_PASSWORD` (A, B). Never commit `.env`.
Every setting: [docs/CONFIG.md](docs/CONFIG.md).

### Profile A: docker compose

Needs Docker with Compose, about 8 GB RAM, about 8 GB disk for models. Choose **one** of `gpu` (NVIDIA GPU with
the container toolkit) or `cpu`; add `tts` for spoken questions and set `JOURNAL_TTS_PROVIDER=piper` in `.env` (default is `none`).

Windows PowerShell:
```powershell
Copy-Item .env.example .env          # then edit .env: set JOURNAL_DB_PASSWORD
docker compose --profile cpu --profile tts up --build -d
# GPU instead:  docker compose --profile gpu --profile tts up --build -d
# without speech output (keep the default JOURNAL_TTS_PROVIDER=none):  docker compose --profile cpu up --build -d
```
sh (Linux/macOS):
```sh
cp .env.example .env                 # then edit .env: set JOURNAL_DB_PASSWORD
mkdir -p data/audio data/tts-cache data/whisper data/piper data/ollama
docker compose --profile cpu --profile tts up --build -d
```
Open http://127.0.0.1:8081. The first start downloads models (Ollama registry, Hugging Face: Whisper small and two
Piper voices); the Whisper service becomes healthy once its model is cached. Check with `docker compose ps`. Stop with
`docker compose --profile cpu --profile tts down`. All ports are published on `127.0.0.1` only. On Linux set
`JOURNAL_UID`/`JOURNAL_GID` in `.env` to your user if it is not 1001.

Existing database from an earlier version? Read "Securing an existing database" in [docs/PRIVACY.md](docs/PRIVACY.md).

### Profile B: plain Java

Run PostgreSQL with pgvector (for example `docker compose up -d db`, published on `127.0.0.1:15432`), Ollama
(`ollama pull qwen3:1.7b qwen3-embedding:0.6b`), a Whisper service and optionally Piper yourself, then set their
endpoints in `.env` (defaults: `localhost:11434`, `:8000`, `:5000`). Needs JDK 25 (`JAVA_HOME`).

Windows PowerShell (two terminals):
```powershell
scripts\run-backend.ps1       # builds with mvnw on first run (-Build to force)
scripts\run-frontend.ps1
```
sh:
```sh
sh scripts/run-backend.sh     # --build to force
sh scripts/run-frontend.sh
```
Open http://127.0.0.1:8081.

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
  typed confirmation (Settings, or `POST /api/data/delete-all`).
- Profile C relaxes this: see the caveat in [docs/PRIVACY.md](docs/PRIVACY.md).

## Development
- `scripts\verify.ps1` / `sh scripts/verify.sh`: build and all tests (no network after dependencies are cached, no Docker).
- `scripts\smoke-compose.ps1` / `sh scripts/smoke-compose.sh`: opt-in Docker smoke test (builds the images, starts
  db, backend and frontend with fake providers in a throwaway project, then removes everything).
- Docs: [PLAN](docs/PLAN.md), [DECISIONS](docs/DECISIONS.md), [CONVENTIONS](docs/CONVENTIONS.md),
  [CONFIG](docs/CONFIG.md), [PRIVACY](docs/PRIVACY.md), [PI](docs/PI.md).
