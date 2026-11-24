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

Known gaps carried forward: real Pi measurement; real microphone in a real browser; Piper/TTS client (Wave 2).
