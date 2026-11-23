# AI Diary — Product Specification

Status: **v0.1, approved by the owner 2026-11-23**. Written by the orchestrator from the
owner's brief and the existing app because no spec existed. Everything here is open to change;
sections marked *(assumption)* are guesses the owner should confirm.

---

## 1. Purpose

A private, local-first voice journal that helps one person think more clearly about their own
life. The user speaks or types an entry; the app stores it safely. Only *after* the user says the
entry is finished does an AI coach ask a few good questions. Over time the app helps the user
remember what they learned (spaced retrieval), practise thinking skills, and look back on each
week — without ever pretending to measure them.

## 2. Principles

1. **Your words first.** An entry is persisted the moment it exists (text, audio, or both),
   before any AI touches it. AI failure never loses user content.
2. **Two stages.** Stage 1: capture (no AI output whatsoever). Stage 2: reflection with the
   coach, started explicitly by the user after finishing an entry.
3. **Questions over answers.** The coach asks; it explains only when explicitly asked.
4. **No gamification of the self.** No streaks, leaderboards, points, levels, "intelligence"
   or wellbeing scores, no generic praise, no medical or psychological claims.
5. **Local by default.** All AI runs locally (Ollama, Whisper, Piper) in the default profiles.
   The only exception is the explicit, opt-in `lite-cloud` profile (§11.3).
6. **Replaceable parts.** Every AI capability sits behind an interface; providers are chosen by
   configuration.

## 3. Users and context

- Single user, self-hosted (desktop/laptop/home server, or Raspberry Pi in the lite profile).
  No accounts or multi-tenancy in v1 *(assumption)*; the app is reachable on localhost only by
  default. Data model keeps a `user_id`-free design but must not hard-code session identifiers.
- Languages: **German and English**. UI language follows the browser (de/en); entry language is
  auto-detected or chosen per entry; the coach always answers in the entry's language.

## 4. Journaling (Stage 1: capture)

### 4.1 Voice entry
- One-tap record button; visible live waveform and elapsed time; pause/resume; stop.
- Audio is recorded in the browser (MediaRecorder, Opus/WebM preferred, fallback to what the
  browser supports) and uploaded in **chunks (≈10 s)** while recording.
- Long recordings (≥ 60 min) must work. If the tab reloads or crashes, already uploaded chunks
  are kept on the server and locally pending chunks (IndexedDB) are uploaded on next load; the
  user can resume or finish the recording.
- When recording stops, the entry exists immediately in state `RECORDED`; a transcription job
  starts in the background.
- Transcription: language `auto`, `de` or `en`. Result is shown for **review and editing**
  before the entry is finished. If transcription fails, the entry stays with its audio, shows a
  calm failure notice, and offers *retry* and *type it yourself*.
- Audio retention is a user setting: *keep audio* (default) or *delete audio after the
  transcript is confirmed*. Audio can be deleted per entry at any time.

### 4.2 Text entry
- A plain, distraction-free text area; autosave as draft every few seconds and on blur.

### 4.3 Entry lifecycle
`DRAFT → (RECORDED → TRANSCRIBING → TRANSCRIBED | TRANSCRIPTION_FAILED) → FINISHED`
- `FINISHED` is set only by the user ("Finish entry"). Editing a finished entry is allowed and
  does not re-trigger anything automatically.
- On finish the entry is embedded for retrieval (background, failure is non-fatal and retried).
- Entries list: newest first, paged, with date, first line, duration if voice, and badges for
  "has coach conversation". Existing entries (pre-v1, with stored stoic AI remarks) stay
  visible; their old AI text is shown collapsed as "earlier AI remark".

### 4.4 Hard rule
**No AI output is generated, shown or spoken while an entry is being recorded or written.**
No auto-analysis of entries. There is no code path from saving/recording to the LLM except
transcription and (after finish) embedding.

## 5. Coach (Stage 2: reflection) — behavioral rules

After "Finish entry" the user may choose **Reflect with coach** (never automatic; the app may
offer it once, quietly). The coach:

1. Starts by asking **2–3 open questions** grounded in the entry (and, if relevant, up to 3
   similar past entries retrieved from the user's own journal). No summary or interpretation
   first.
2. Asks **one question at a time** in the conversation; follow-ups adapt to the answers
   (go deeper, clarify, or move to the next question).
3. **Questions over explanations.** It gives explanations, advice, or information **only when
   the user explicitly asks** for them (e.g. "explain", "what do you think", "erklär mir").
   Then briefly, and it returns to a question afterwards.
4. **No generic praise** ("Great insight!", "Toll!"), no flattery, no moralizing, no
   diagnoses, no therapy claims, no scores or ratings of the user.
5. Uses the user's own words when referring back; never invents facts about the user's life.
6. Matches the entry language (de/en); short sentences; at most ~60 words per turn.
7. If the entry signals acute crisis or self-harm, the coach stops questioning and shows a
   short, fixed, non-AI message with crisis resources (localized). *(assumption)*
8. **Never interrupts.** The coach never speaks or posts while the user is recording or typing
   an answer; TTS playback starts only when no recording is active.
9. Conversations can be **paused and resumed** any time; full transcript (questions, answers,
   timestamps) is stored and viewable; the user can end the conversation explicitly.
10. Answers may be voice (transcribed via the same pipeline) or text.

LLM output that is persisted (questions, follow-ups) is requested as structured JSON, validated
against a schema, retried (max 2) and otherwise rejected with a visible, calm error.

## 6. Memory (learning what you've learned)

- From a finished entry or coach conversation the user can run **Extract learnings**. The LLM
  proposes candidate knowledge items (JSON: `prompt`, `answer`, `sourceQuote`, `type` =
  fact | insight | principle | decision). Nothing is saved until the user **approves, edits or
  discards** each candidate.
- Approved items become **memory items** scheduled with **SM-2**. Initial intervals are
  configurable, default **1, 3, 7, 14, 30 days**; afterwards SM-2 ease-factor progression.
  Recall quality 0–5 (UI shows four buttons: *forgot*, *hard*, *good*, *easy* → 1, 3, 4, 5).
- **Retrieval practice**: the review screen shows only the prompt. The source entry/answer is
  **hidden until the user has attempted an answer** (typed or "I tried in my head").
- **Memory technique lessons**: short progressive lessons (e.g. retrieval practice, spacing,
  elaboration, chunking, method of loci, dual coding), unlocked one after another by usage, not
  by scores. Static content, available offline in de/en.
- Review counts are shown as "due today: n" — no streaks.

## 7. Thinking Gym

Short, personalized exercises (**5–10 minutes**) built from the user's journal history, in
**7 categories**:

1. **Assumption check** — find and test the assumptions behind a past statement.
2. **Perspective shift** — retell a situation from another person's viewpoint.
3. **Reframing** — find two alternative interpretations of an event.
4. **Decision review** — pre-mortem / options-and-criteria for a pending decision.
5. **Second-order thinking** — "and then what?" consequences of a choice.
6. **Bias spotting** — identify a possible cognitive bias in a described judgement.
7. **Explain it simply** — explain something you learned in plain words (Feynman).

- Each exercise has a prompt, optional source excerpt, steps, and a free-text answer field.
- **Completable without AI**: a built-in library of template exercises (de/en) is used when no
  LLM is available; personalization uses the LLM (structured JSON) when available.
- Optional AI feedback on an attempt only on request and in question form (§5 rules apply).
- History of attempts per exercise and category; no scores.

## 8. Weekly reflection

Generated on demand (and optionally offered on Sunday) for a calendar week, from that week's
finished entries and coach conversations. Sections:

1. **Recurring themes** — 2–4 themes, each with references to the entries.
2. **In your own words** — 2–3 short quotes from the user's entries.
3. **Open threads** — unresolved questions or decisions the user mentioned.
4. **What you learned** — memory items created/reviewed this week (counts, no grading).
5. **Noticed patterns** — descriptive observations only (time, topics, people), no diagnosis.
6. **A question for next week** — one open question.

No scores, mood ratings, health or productivity claims. Output is structured JSON, validated,
stored with the generation date; the user can regenerate, edit and delete. Historical view
lists past weeks.

## 9. Voice output (TTS)

- Coach questions can be read aloud (Piper locally; OpenAI only in `lite-cloud`). Auto-play is a
  setting (default on); never during an active recording (§5.8).
- Voice per language selectable; audio cached by (text, voice) hash; on failure the text is
  shown silently (graceful fallback).

## 10. Privacy and data

- No telemetry, analytics, crash reporting or external fonts/CDNs. No journal content in logs.
- All published ports bind to `127.0.0.1` by default. CORS restricted to the configured
  frontend origin.
- **Export**: one-click export of all data (entries, transcripts, conversations, memory items,
  reviews, gym attempts, reports) as JSON in a zip, audio optional.
- **Delete everything**: one action (with typed confirmation) deletes all user data including
  audio files, embeddings and caches.
- docs/PRIVACY.md documents what stays local, how to delete, and the `lite-cloud` caveat.

## 11. Deployment profiles

### 11.1 Profile A — docker-compose (full local)
ollama, postgres+pgvector, whisper (faster-whisper with an OpenAI-compatible HTTP API), piper
TTS, backend, frontend. Compose profiles select GPU/CPU variants and optional services.
Secrets via `.env` (`.env.example` committed). Ports on 127.0.0.1.

### 11.2 Profile B — plain Java
Backend and frontend via `java -jar` / mvnw against externally running Postgres, Ollama,
Whisper and Piper; every endpoint configurable by env var / properties. Scripts:
`scripts/run-*.ps1` (primary) and `scripts/run-*.sh`.

### 11.3 Profile C — `lite-cloud` (Raspberry Pi 3, 1 GB RAM, 64-bit OS only)
- No local AI. Chat, embeddings, transcription and TTS use the OpenAI API.
- **Explicit opt-in**: profile `lite-cloud` + `OPENAI_API_KEY` + consent flag
  (`JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`). Startup fails without both.
- Persistent in-UI banner: entry text and audio are sent to OpenAI.
- Embedded DB (H2 file) and an in-JVM vector store (cosine similarity over stored floats).
- Never the default; impossible to activate in A/B by accident.
- systemd unit and Pi setup guide (docs/PI.md). 32-bit OS unsupported.

## 12. Navigation and UX

Calm, minimalist, text-first. Light/dark (follows system, overridable). Responsive (phone →
desktop). Accessible: WCAG 2.1 AA contrast, labels, visible focus, full keyboard use,
`prefers-reduced-motion` respected (waveform becomes a level bar).

Primary navigation (side drawer on desktop, bottom bar on mobile):

| Route | Name (en / de) | Content |
|---|---|---|
| `/` | Today / Heute | New entry (record or write), today's entries |
| `/entries` | Journal / Tagebuch | Entries list, entry detail, transcript edit |
| `/entries/{id}/coach` | Reflect / Reflektieren | Coach conversation for an entry |
| `/memory` | Remember / Erinnern | Due reviews, memory items, lessons |
| `/gym` | Thinking Gym / Denktraining | Exercises, history |
| `/weekly` | Week / Woche | Weekly reflections, history |
| `/settings` | Settings / Einstellungen | Language, voice, audio retention, intervals, export, delete all, profile info |

The `lite-cloud` banner is shown on every route when active.

## 13. Non-functional

- Recording upload tolerates network drops; chunk upload is idempotent (chunk index).
- Backend responds to entry save < 300 ms excluding transcription.
- Pi lite profile: total RSS of all app processes ≤ 750 MB measured (docs/PI.md).
- Tests run without network and without Docker.

## 14. Out of scope (v1)

Multi-user/auth, mobile native apps, sync between devices, end-to-end encryption at rest
*(candidate for v2)*, sharing, notifications/push.

## 15. Acceptance criteria

| # | Criterion | Wave |
|---|---|---|
| 1 | A voice entry is recorded with live waveform, uploaded in chunks, survives a page reload mid-recording, is transcribed (de/en/auto), and the transcript can be reviewed and edited before finishing. | 1 |
| 2 | An entry (text or audio) is persisted before any AI call; if transcription fails or the provider is unreachable, the audio and entry remain, the user sees a retry and a manual-text option, and nothing is lost. | 1 |
| 3 | Text entries can be written and saved; the entries list is ordered newest first with paging; pre-existing entries (incl. old AI remarks) are still shown. | 1 |
| 4 | No AI output is produced or shown while recording/writing; no automatic analysis on save; the coach starts only after the user finishes the entry and chooses to reflect, and opens with 2–3 questions. | 2 |
| 5 | Coach conversations accept voice or text answers, can be paused and resumed, store the full transcript, and reply in the entry's language (de/en). | 2 |
| 6 | Coach follows §5: questions over explanations, explanation only on explicit request, no generic praise; questions are spoken via TTS when no recording is active, with silent text fallback on TTS failure. | 2 |
| 7 | "Extract learnings" yields validated candidate items; only user-approved items become memory items; invalid LLM JSON is retried then rejected without persisting anything. | 3 |
| 8 | Reviews hide the source until an answer is attempted; SM-2 scheduling uses configurable initial intervals (default 1/3/7/14/30) and adapts to recall quality; technique lessons unlock progressively. | 3 |
| 9 | Thinking Gym offers 5–10 minute exercises across all 7 categories, personalized when an LLM is available, fully usable without AI, with an attempt history. | 3 |
| 10 | Weekly reflection contains the 6 sections of §8, no scores or health claims, and past weeks are browsable. | 3 |
| 11 | Privacy: in profiles A/B no outbound connections except configured local endpoints; no journal content in logs; ports bound to 127.0.0.1; export produces all data; delete-all removes all data including audio and embeddings. | 1 |
| 12 | Deployment: profiles A, B and `lite-cloud` start with the documented commands; misconfigured providers fail fast at startup with a clear message; `lite-cloud` refuses to start without API key and consent and shows the cloud banner. | 1 |
