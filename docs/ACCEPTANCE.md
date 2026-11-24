# Acceptance

Maps every criterion of SPEC §15 to automated tests and manual evidence. Completed per wave; final review in Wave 4.

## Wave 1 (run 2026-11-23/24 on main after merging W1a–W1e)
Manual run: throwaway compose project (`db`, `backend`, `frontend`, `whisper-cpu` = Speaches 0.8.3 with
Systran/faster-whisper-small), temp data dirs, LLM/embedding fake. German speech sample from Windows TTS.

| # | Criterion (short) | Automated tests | Manual evidence | Result |
|---|---|---|---|---|
| 1 | Voice entry: waveform, chunked upload, survives reload, transcribed de/en/auto, transcript editable | `RecordingFlowTest` (out-of-order/duplicate chunks, resume, complete), `TranscriptionJobTest`, `WhisperHttpSpeechToTextTest`, JS `recorder/*.test.mjs` (state machine, chunk queue, resume after reload, crash during upload) | 3 chunks uploaded out of order + duplicate through the frontend proxy; resume info `0,1,2`; real Whisper returned the exact German text, language DE; Today view shows recorder | **Pass** (real microphone + IndexedDB in a real browser not yet exercised; W1b used a synthetic audio stream) |
| 2 | Entry persisted before AI; transcription failure keeps audio, offers retry/manual text | `EntryResourceTest.entryIsPersistedImmediatelyWithoutAnyAiCall`, `RecordingFlowTest`, `TranscriptionJobTest` failure/retry paths | Whisper stopped: 3 attempts, `PROVIDER_UNREACHABLE`, entry `TRANSCRIPTION_FAILED`, audio still served (294956 bytes); retry after restart → TRANSCRIBED | **Pass** |
| 3 | Text entries saved; list newest first, paged; legacy entries shown | `EntryResourceTest` (paging, order, legacy remark), `LegacyDatabaseMigrationTest`, Wave 0 manual PG legacy upgrade | Typing autosaved a DRAFT without pressing a button; finish; Journal list newest first | **Pass**. Defect found (server UTC time on entries) fixed by W1b + orchestrator; rechecked in the browser on compose: entry stored at local time |
| 11 | Privacy: no outbound calls, no content in logs, 127.0.0.1, export, delete-all | `NoContentInLogsTest`, `PrivacyResourceTest`, provider log tests | 166 backend/frontend log lines contain none of the entry words; all ports `127.0.0.1:*`; browser traffic only to 127.0.0.1; export zip had entries, recordings, jobs, settings, legacy, audio, manifest; wrong phrase 400; delete-all → 0 entries, 0 audio files | **Pass** |
| 12 | Profiles start; misconfiguration fails fast; lite-cloud needs key + consent, shows banner | `ConfigValidatorTest`, `ProviderDefaultsTest`, `CloudBanner`/shell tests, `scripts/smoke-compose.ps1` | Compose backend with `JOURNAL_LLM_PROVIDER=openai` refuses to start with the external-service message; lite build refuses without consent (Wave 0, W1d); compose smoke green | **Pass** (profile C on real Pi hardware: Wave 4) |

Known gaps carried forward: real Pi measurement; real microphone in a real browser.

## Wave 2 (run 2026-11-24 on main after merging W2a–W2c)
Manual run: throwaway compose project with profiles `cpu` + `tts` (Ollama 0.20 with qwen3:1.7b and
qwen3-embedding:0.6b on CPU, Speaches Whisper, Piper with de/en voices), temp data dirs, all real providers.

| # | Criterion (short) | Automated tests | Manual evidence | Result |
|---|---|---|---|---|
| 4 | No AI during capture; coach only after finish + explicit start; 2–3 opening questions | `CoachFlowTest` (no LLM after save/finish/transcription, 409 unfinished, 1/4 questions rejected), `CoachPromptContractTest` | 0 `/api/chat` calls in the Ollama log after create, update and finish; start before finish → 409; start after finish → 1 German question shown (17 s on CPU), the next revealed after answers | **Pass** |
| 5 | Voice or text answers, pause/resume, full transcript, language match | `CoachFlowTest` lifecycle, `TranscribeResourceTest`, `CoachPresenterTest`, JS `coach/*.test.mjs` | Text answers; pause → answering gives 409 → resume; spoken answer via `/api/transcribe` (real Whisper, DE) stored with the voice flag; coach screen shows the full transcript with "per Sprache" badge and local times; all turns German | **Pass** |
| 6 | Questions over explanations, explanation only on request, no praise; TTS when no recording, silent fallback | `CoachRulesTest`, `CoachPromptContractTest`, `TtsResourceTest` (204 fallback, cache), JS `recording-guard`/`playback-queue` tests | Unprompted turns were always questions; explicit requests ("Kannst du mir erklären…", "Was meinst du…") → short explanation ending in one question (after the fix below); Piper `audio/wav` for de/en, cache hit 27 ms; "Vorlesen" in the UI → `/upload/tts` 200 | **Pass** after fix: explicit requests were answered with a question by qwen3:1.7b; now the schema requires EXPLANATION and checks that it explains |
| 11 | (re-check) no content in logs | — | backend, frontend, Ollama (2866 lines), Whisper, Piper logs contain none of the conversation's words | **Pass** |

Observed model quality (qwen3:1.7b, CPU): questions are relevant but sometimes awkward (once phrased in the first person,
"Wie könnte ich…"). A larger local model (e.g. qwen3:4b/8b, GPU) is likely to read better; owner's choice (`journal.llm.ollama.model`).
Not checked in Wave 2: real microphone for voice answers, TTS audio audibly playing, the recording guard with a real recording.

## Wave 3 (run 2026-11-24 on main after merging W3a–W3c)
Manual run: throwaway compose project (db, backend, frontend, Ollama 0.20 qwen3:1.7b + qwen3-embedding:0.6b on CPU), temp
data dirs, three finished German entries in ISO week 2026-W48.

| # | Criterion (short) | Automated tests | Manual evidence | Result |
|---|---|---|---|---|
| 7 | Extraction yields validated candidates; only approved ones become items; invalid JSON retried then rejected | `MemoryFlowTest` (invented quote, invalid JSON thrice → 502, approve-only) | Real extraction (23 s) gave a candidate with a verbatim quote; 0 items before approval; approve (edited answer) → 1 item, discard → none; deciding twice → 409 | **Pass** |
| 8 | Source hidden until attempt; SM-2 with configurable ladder 1/3/7/14/30; lessons unlock progressively | `Sm2SchedulerTest` (exhaustive), `MemoryFlowTest`, `ReviewSchedulerContract` | New item: interval 1 d, EF 2.5; due JSON contained only the prompt; grade before attempt → 409; attempt revealed answer + verbatim quote; grade 4 → interval 3 d; lessons 1–2 open, 3–6 locked with calm German hints | **Pass** |
| 9 | 5–10 min exercises across 7 categories, personalized with LLM, usable without AI, history | `GymFlowTest`/template-library tests | With Ollama: personalized exercise (11 s, 7 min) built on a verbatim journal excerpt; feedback only on request (2 questions); Ollama stopped: 7 picks rotated through all remaining categories from templates; attempt history kept | **Pass** (content quality of qwen3:1.7b weak, see below) |
| 10 | Weekly reflection with the six §8 sections, no scores/health claims, history | `WeeklyFlowTest`, `WeeklyRulesTest`, ISO week tests | 2026-W48 generated in 36 s, German, all six sections; quotes verbatim; "what you learned" = 1 item, 1 review from MemoryStats; empty week → 409; future week → 400; history lists W48 | **Pass** |
| 11 | (re-check) no content in logs | — | backend, frontend, Ollama logs contain none of the entry words | **Pass** |

Browser: /memory, /gym, /weekly render with the real data, no console errors.
Model quality (qwen3:1.7b): technically valid but often clumsy output (first-person phrasing in exercises, open threads and
the weekly question; gym steps phrased as questions). The validators keep it safe, not good. Recommendation for the owner:
a larger local model (e.g. qwen3:4b/8b on the GPU, `JOURNAL_LLM_OLLAMA_MODEL`).
