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
