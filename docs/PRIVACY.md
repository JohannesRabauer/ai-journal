# Privacy

AI Diary is a local-first journal. There is no telemetry, analytics, crash reporting, external font or CDN. The only
outbound connections are the provider endpoints you configure.

## What stays local, per profile

| | A: docker compose | B: plain Java | C: `lite-cloud` |
|---|---|---|---|
| Entry text, transcripts, conversations, memory | local PostgreSQL | local PostgreSQL | local H2 file |
| Audio recordings | `./data/audio` | `JOURNAL_AUDIO_FILESYSTEM_DIRECTORY` | `/var/lib/ai-diary/audio` |
| Transcription, questions, embeddings, speech | Whisper, Ollama, Piper on your machine | same, services you run | **OpenAI API** |
| Outbound connections | none while running. One-time model downloads (Ollama registry, Hugging Face) on first start | none | OpenAI (`api.openai.com`) |
| Ports | published on `127.0.0.1` only | backend/frontend bind `127.0.0.1` by default | frontend binds `0.0.0.0` in the systemd unit (home network), change if unwanted |

Models are downloaded once. After that you can disconnect the machine; profiles A and B keep working. To stop even the
one-time downloads, pre-populate `./data/ollama`, `./data/whisper`, `./data/piper` and set `HF_HUB_OFFLINE=1` for the
whisper container.

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
| Database (profile A) | `./db/data` (or `JOURNAL_DB_DATA_DIR`) |
| Audio files (A) | `./data/audio` |
| Ollama models, Whisper model, Piper voices (A) | `./data/ollama`, `./data/whisper`, `./data/piper` (models only, no journal data) |
| Database (B) | your PostgreSQL |
| Database and audio (C) | `/var/lib/ai-diary/journal.mv.db`, `/var/lib/ai-diary/audio` |
| Embeddings | in the database (vector tables); in `lite-cloud` also in the H2 file |

## Export

Settings page -> Export, or:

```
curl -o ai-diary-export.zip "http://127.0.0.1:8080/api/data/export?includeAudio=true"
```

(`includeAudio=false` is the default.)
The zip holds one JSON file per data owner (`entries.json`, `settings.json`, `coach/...`, `legacy/...`) and
audio files when requested, plus `manifest.json` (export time, profile, owners and whether each succeeded, `complete`).
Embeddings are derived data and are not exported (they are rebuilt from the entries). If one owner fails, the export
continues, marks it `failed` in the manifest and sets `complete=false`; check the manifest.

## Delete everything

Settings page -> Delete all (you type `DELETE EVERYTHING`), or:

```
curl -X POST -H "Content-Type: application/json" -d "{\"confirmation\":\"DELETE EVERYTHING\"}" http://127.0.0.1:8080/api/data/delete-all
```

This removes entries, transcripts, conversations, memory items, reviews, reports, settings, the old pre-v1 tables,
vector embeddings and all audio files. The response lists how many rows/files each part deleted. A wrong phrase returns
400 and deletes nothing. If a part fails, the others are still deleted and the call returns 500 `DELETE_INCOMPLETE`
naming the failed parts; run it again (it is idempotent) or check the server log. Export first if you want a copy.

What the endpoint does not touch: downloaded models, the `./db/data` directory itself (empty tables remain), database
backups you made (`./backups`), exports you saved, and PostgreSQL's own files (deleted rows are reclaimed by vacuum,
not overwritten). For a full wipe stop the stack and remove the data:

```
docker compose down -v
# Windows PowerShell
Remove-Item -Recurse -Force data, db\data
# Linux / macOS
rm -rf data db/data
```

On the Pi: `sudo systemctl stop ai-diary-frontend ai-diary-backend && sudo rm -rf /var/lib/ai-diary`.
(Never do this to `db/data` before you have an export or backup you want to keep.)

## Securing an existing database

Databases created before v1 were initialized by a compose file that used PostgreSQL `trust` authentication: any process
that could reach the (then publicly published) port could connect without a password. New v1 databases get a password
from `.env`, but an **existing** data directory keeps its old `pg_hba.conf`; the new `JOURNAL_DB_PASSWORD` is not applied
to it. Fix it once:

1. Stop the backend: `docker compose stop backend frontend` (leave `db` running).
2. In `.env` set `JOURNAL_DB_PASSWORD` to a new long random value (not the example value).
3. **Back up first.** The script does it for you (a `pg_dump` into `./backups`, mode 0600) unless you pass
   `-SkipBackup` / `SKIP_BACKUP=1`. You can also copy the data directory while the stack is stopped.
4. Run the script (it never deletes or rewrites data):
   - Windows: `scripts\secure-existing-db.ps1`
   - Linux/macOS: `sh scripts/secure-existing-db.sh`
   It sets the password of the `ai-diary` role, copies `pg_hba.conf` to `pg_hba.conf.bak-before-secure`, replaces every
   `trust` rule with `scram-sha-256`, reloads PostgreSQL and verifies that the right password works and a wrong one is
   rejected. Pass a container name to secure a database that does not belong to this compose project.
5. Start everything: `docker compose up -d`.

To restore the backup into a fresh database: `docker compose exec -T db pg_restore -U ai-diary -d ai-diary --clean --if-exists < backups\<file>.dump`
(in PowerShell use `cmd /c "docker compose exec -T db pg_restore ... < file"`).
Profile B users with their own PostgreSQL: set a password for the role (`ALTER ROLE "ai-diary" PASSWORD '...'`) and change
`trust` to `scram-sha-256` in `pg_hba.conf` manually.
