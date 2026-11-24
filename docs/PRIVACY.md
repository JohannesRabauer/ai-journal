# Privacy

AI Diary is a local-first journal. There is no telemetry, analytics, crash reporting, external font or CDN. The only
outbound connections are the provider endpoints you configure.

## What stays local, per profile

| | A: docker compose | B: plain Java | C: `lite-cloud` |
|---|---|---|---|
| Entry text, transcripts, conversations, memory | local H2 file | local H2 file | local H2 file |
| Audio recordings | `./data/audio` | `JOURNAL_AUDIO_FILESYSTEM_DIRECTORY` | `/var/lib/ai-diary/audio` |
| Transcription, questions, embeddings, speech | Whisper, Ollama, Piper on your machine | same, services you run | **OpenAI API** |
| Outbound connections | none while running. Model downloads (Ollama registry, Hugging Face) only while a model is missing, i.e. on first start | none | OpenAI (`api.openai.com`) |
| Ports | the backend (API and web pages, one process) published on `127.0.0.1` only (the H2 database is embedded in the backend and has no port); Ollama, Whisper and Piper publish no port at all (only the other containers reach them) | the backend binds `127.0.0.1` by default | the unit binds `127.0.0.1` (SSH tunnel). Opening the pages to the home network is an explicit opt-in (`JOURNAL_HTTP_HOST=0.0.0.0` and `JOURNAL_ALLOWED_HOSTS`, docs/PI.md) |

Models are downloaded once. After that you can disconnect the machine; profiles A and B keep working. The containers
check what is cached before they connect anywhere: the Ollama container runs `ollama pull` only for models that
`ollama list` does not show (a pull contacts the registry even for a present model), the Whisper container skips the
download call when the model directory exists in `./data/whisper` and then starts with `HF_HUB_OFFLINE=1` (set
`WHISPER_ALLOW_ONLINE=1` to keep the client online), and Piper downloads only missing voices. To avoid even the first
downloads, pre-populate `./data/ollama`, `./data/whisper`, `./data/piper`.

## Protection of the local API (no login in v1)

The journal has no authentication, so the servers defend themselves against other websites open in the same browser:

- **Cross-site writes (CSRF).** Every request other than GET/HEAD/OPTIONS to the backend (`:8080`, which serves both the API and
  the pages) must carry the header `X-AI-Diary: 1`. A browser cannot add a custom header to a request to
  another origin without a CORS preflight, and CORS is disabled, so forms, `sendBeacon` and `fetch(..., {mode: 'no-cors'})`
  from other sites cannot write. Missing header: `403` with code `CSRF_HEADER_REQUIRED`. The browser scripts of the pages send
  it (HTML form posts use a per-session token instead); **your own curl / Invoke-RestMethod calls must send it too** (see the examples in this file).
- **Origin and Fetch Metadata.** A request with an `Origin` header outside the allowlist (`journal.http.allowed-origins`,
  empty by default) or with `Sec-Fetch-Site: cross-site` is rejected with `403`, also for reads.
- **DNS rebinding.** The backend checks the `Host` header: only `localhost`, `127.0.0.1`, `[::1]` (any port), the compose
  service name `backend` and the names in `JOURNAL_ALLOWED_HOSTS` are served; anything else gets `421`
  without details.
- **Framing and sniffing.** The pages are sent with `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`,
  `Referrer-Policy: no-referrer` and `X-Content-Type-Options: nosniff` on every response; audio responses are `nosniff` too.
  Recording uploads accept only audio MIME types (`audio/webm`, `ogg`, `mp4`, `mpeg`, `wav`, `x-wav`, `flac`).

## Deleting while background work is running

Delete-all advances a global "purge generation" before it starts and again when it ends. Background writers that derive
data from journal text capture the generation when they read their input and drop their result when it changed: the
entry indexer (and therefore the backfill) and the weekly reflection generation. The indexer also re-checks that the entry
still exists when it writes, so deleting a single entry while it is being embedded leaves no vectors behind; the weekly
report drops links and quotes of entries that no longer exist (and is not saved when none remain). Not covered: speech
transcription jobs (they update the recording's own entry, which delete-all removes, so a late result finds nothing to
update) and synchronous, user-started LLM calls of the coach, gym and memory features, where a result that arrives after a
delete-all started could still be stored; run delete-all again to clear it.

## The `lite-cloud` caveat

In `lite-cloud` your **entry text and audio are sent to OpenAI** for transcription, coach questions, embeddings and
speech. That relaxes the local-only guarantee: OpenAI's own retention and privacy terms apply to that data. For this
reason the profile is never the default and needs three explicit opt-ins: a separate build, `OPENAI_API_KEY` and
`JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`. Without them startup fails. While it is active the UI
shows a permanent banner on every page. Everything else (database, audio files, export, delete) stays on the device.
Do not use this profile for entries you would not send to a third party.

## Logs

Logs never contain journal content (entry text, transcripts, prompts, model output, audio). They contain ids, sizes,
counts, provider names and error kinds. SQL and bind-parameter logging is off. The Whisper service runs with log level
`info` (the upstream default `debug` is overridden), the Piper service logs only voice and character counts, and the
`OLLAMA_DEBUG` flag is deliberately not set (D-015). A test (`NoContentInLogsTest`, `PrivacyResourceTest`) asserts this
for the main flows including failures.

## Where the data lives

| Data | Location |
|---|---|
| Database (profile A) | `./data/db/journal.mv.db` (or `JOURNAL_DB_DATA_DIR`) |
| Audio files (A) | `./data/audio` |
| Ollama models, Whisper model, Piper voices (A) | `./data/ollama`, `./data/whisper`, `./data/piper` (models only, no journal data) |
| Database (B) | `./data/journal.mv.db` (or the path in `JOURNAL_DB_URL`) |
| Database and audio (C) | `/var/lib/ai-diary/journal.mv.db`, `/var/lib/ai-diary/audio` |
| Embeddings | in the same H2 file (vector table `vector_chunk`) |

## Export

Settings page -> Export, or:

```
curl -o ai-diary-export.zip "http://127.0.0.1:8080/api/data/export?includeAudio=true"
```

(`includeAudio=false` is the default.)
The Settings page streams the zip from the backend directly into the browser download; no temporary copy is written on the
server (older versions used a temp file, which is swept at startup). The zip holds one JSON file per data owner
(`entries.json`, `settings.json`, `coach/...`, `legacy/chat_history.json`, `legacy/diary.json` with text and metadata of the
pre-v1 `diary` table, when present) and
audio files when requested, plus `manifest.json` (export time, profile, owners and whether each succeeded, `complete`).
Embeddings are derived data and are not exported (they are rebuilt from the entries). If one owner fails, the export
continues, marks it `failed` in the manifest and sets `complete=false`; check the manifest.

## Delete everything

Settings page -> Delete all (you type `DELETE EVERYTHING`), or:

```
curl -X POST -H "X-AI-Diary: 1" -H "Content-Type: application/json" -d "{\"confirmation\":\"DELETE EVERYTHING\"}" http://127.0.0.1:8080/api/data/delete-all
```

This removes entries, transcripts, conversations, memory items, reviews, reports, settings, the old pre-v1 tables,
vector embeddings (`vector_chunk`) and all audio files. The response lists how many rows/files each part deleted. A wrong phrase returns
400 and deletes nothing. If a part fails, the others are still deleted and the call returns 500 `DELETE_INCOMPLETE`
naming the failed parts; run it again (it is idempotent) or check the server log. Export first if you want a copy.

Legacy `diary` rows (the pre-v1 table) are not linked to entries by id: deleting a single entry cannot always
find its legacy rows (it can only when their metadata names the entry). Delete-all removes all of them.

What the endpoint does not touch: downloaded models, the database file itself (`journal.mv.db`, empty tables remain; H2 reuses freed space, it does not overwrite it), backups
you made, exports you saved. For a full wipe stop the stack and remove the data:

```
docker compose down -v
# Windows PowerShell
Remove-Item -Recurse -Force data
# Linux / macOS
rm -rf data
```

On the Pi: `sudo systemctl stop ai-diary-backend && sudo rm -rf /var/lib/ai-diary`.
(Never do this to `data` (the H2 file and audio) before you have an export or backup you want to keep.)

## Importing data from the old PostgreSQL version

Earlier versions stored the journal in PostgreSQL + pgvector (D-032 replaced it by the embedded H2 file). To bring that data
over, stop the backend and run `pwsh scripts/import-legacy-postgres.ps1 -DataDir db/data_personal` (sh twin:
`sh scripts/import-legacy-postgres.sh <datadir>`). It works on a COPY of the old data directory with a temporary
container, loads the legacy tables `documents` and `chat_history` into the H2 file database and stops nothing else. Delete the
old PostgreSQL data directory yourself when you no longer need it.
