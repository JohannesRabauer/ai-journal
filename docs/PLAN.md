# Plan

Source of truth for behavior: [SPEC.md](SPEC.md) (draft v0.1, awaiting owner review).
Decisions: [DECISIONS.md](DECISIONS.md). Status: [PROGRESS.md](PROGRESS.md). Rules for every change: [CONVENTIONS.md](CONVENTIONS.md).

## Starting point (inspected 2026-11-23)
Quarkus 3.29 backend (quarkus-langchain4j Ollama + pgvector, Panache, Hibernate `update`), Vaadin 24.9 + Spring Boot 3.5
frontend with one `MainView`, compose with ollama/db/backend/frontend. Problems found as briefed, plus: Ollama ran with
`OLLAMA_DEBUG=2` (may log prompts), root context had no `.dockerignore` protection for `db/data`.

## Architecture
```
browser ──same origin──> frontend (Vaadin/Spring, :8081) ──REST──> backend (Quarkus, :8080) ──> DB (Postgres | H2)
                         └─ /upload proxy for audio chunks (W1b)          └─ SPI providers ──> Ollama/Whisper/Piper | OpenAI (lite-cloud only)
```
- `api/` shared module: REST DTO records/enums used by both sides (contract owned by the orchestrator).
- Backend packages: `spi` (interfaces + `spi.fake`), `config` (provider selection, startup validation), `rest`
  (JAX-RS contract interfaces + `rest.stub` 501 placeholders), `llm` (StructuredOutputService), `privacy`
  (UserDataOwner), feature packages (`entry`, `settings`, `system`, later `audio`, `transcription`, `coach`, `tts`,
  `memory`, `gym`, `insights`).
- Schema: Flyway, per-vendor folders `db/migration/{postgresql,h2}`, version ranges per worker.

## Waves
| Wave | Work | Acceptance (SPEC §15) |
|---|---|---|
| 0 (orchestrator) | spec draft, Pi spike, Flyway baseline, contracts + fakes, provider config + validation, hardening, verify scripts, stoic analysis removed | – |
| 1 | W1a STT + recordings + AudioStorage · W1b recorder UI + entries UI · W1c design shell · W1d deployment + privacy endpoints · **W1e LLM/embedding/vector providers + indexing backfill** (added, see D-009) | 1–3, 11, 12 |
| 2 | W2a coach backend · W2b TTS · W2c coach UI | 4–6 |
| 3 | W3a memory + SM-2 · W3b thinking gym · W3c weekly insights | 7–10 |
| 4 | e2e smoke A/B/lite, accessibility pass, ACCEPTANCE.md mapping, security/privacy review | all |

## Ownership (Wave 1)
| Worker | Owns (create/modify only these) |
|---|---|
| W1a | backend `audio/`, `transcription/`, `spi/stt/`, `spi/audio/`; delete `rest/stub/RecordingsApiStub`, `TranscriptionsApiStub`; migrations V10–V19 |
| W1b | frontend `recorder/` (Java), `src/main/frontend/recorder/`, `views/entries/`, `client/RecordingsClient`, `client/TranscriptionsClient`, upload proxy controller; JS tests |
| W1c | frontend `shell/`, `views/` placeholders, theme `src/main/frontend/themes/`, `MainView` replacement, `client/SystemClient`, `client/SettingsClient` |
| W1d | `docker-compose.yml`, `*/Dockerfile`, `whisper/`, `piper/`, `scripts/run-*`, `scripts/smoke-*`, `deploy/`, `.env.example`, README, docs `PI.md` `PRIVACY.md` `CONFIG.md`; backend `privacy/` service+resource; delete `PrivacyApiStub`; migrations V20–V29 |
| W1e | backend `spi/llm/`, `spi/embedding/`, `spi/vector/`, `entry/EntryIndexer` backfill; migrations V30–V39 |

Shared hot spots resolved by the orchestrator at merge time: `backend/pom.xml`, `frontend/pom.xml`,
`application.properties` (each worker only appends its own keys under a `# ---- <worker>` header).
