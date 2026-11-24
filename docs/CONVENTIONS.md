# Conventions (every worker, every change)

## Behavior rules (from SPEC; violations block a merge)
- No AI call while recording or writing; no automatic analysis on save. Only transcription, and embedding after finish.
- Persisted LLM output only via `StructuredOutputService` (schema → parse → Bean Validation → semantic check → retry → reject).
- Never log journal content (entry text, transcripts, prompts, LLM output, audio). Log ids, sizes, error kinds only.
  `NoContentInLogsTest` shows the pattern; add a similar assertion for your feature's failure paths.
- No telemetry/analytics, no external fonts/CDNs, no outbound calls except the configured provider endpoints.
- No streaks, scores, leaderboards, generic praise, or health claims in any UI text or prompt.

## Code
- SPI implementations: `@ApplicationScoped @ProviderName("<name>") @io.quarkus.arc.Unremovable`, provider settings
  under `journal.<capability>.<name>.*` with defaults (an unused provider must never break startup). Implement
  `SelfValidating` for URL/format checks (no network), `ExternalProvider` if data leaves the device.
- Every implementation extends the matching abstract contract test in `src/test/java/.../spi/contract/`.
  HTTP providers use WireMock; tests never touch the network or Docker.
- Business code injects interfaces only (`@Inject SpeechToText stt`), never an implementation.
- Every table you add: a `UserDataOwner` bean (export + delete-all) in your package; per-entry rows must also be
  removed by an `@Observes(during = AFTER_SUCCESS) EntryDeleted` observer (D-019).
- REST classes: never root a class at a prefix of another resource's routes; entry-scoped routes go on an interface
  rooted at `/api/entries/{entryId}/...` (D-018).
- Migrations: additive only, your assigned version range, **same file name in `db/migration/postgresql` and `db/migration/h2`**.
- REST: implement the interface in `rest/`; delete the matching `rest/stub/*Stub` in the same commit. Need a contract
  change (`api/` records, `rest/` interfaces, `spi/` interfaces)? Stop and report to the orchestrator.
- Errors to the client: throw `ApiException` (user-presentable message, no content) or let `ProviderException` /
  `InvalidLlmOutputException` propagate to `ErrorMappers`.
- `application.properties`: append only, under a `# ---- <worker id>` header.

## Process
- Work only in your worktree/branch and only in your owned paths.
- `scripts/verify.ps1` (or `.sh`) must pass before you report back.
- Stop every server, container or watcher you started (dev servers, docker compose projects) before you report back.
- Commits: small, conventional (`feat(stt): …`, `test(coach): …`), trailer `Assisted-by: Claude`. Never `Co-authored-by` for Claude.
