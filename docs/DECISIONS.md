# Decisions

Format: ID · decision · why · consequence. Newest last.

**D-001 Spec drafted by the orchestrator.** `docs/SPEC.md` did not exist; the owner chose "draft for review".
Draft v0.1 covers §1–15 incl. 12 acceptance criteria. Wave 1 waits for the owner's approval.

**D-002 Shared `api` module + root aggregator pom.** DTO records shared by backend and frontend instead of duplicated
classes. Docker build context becomes the repo root (protected by `.dockerignore`, which excludes `db/data*`).

**D-003 Flyway with per-vendor folders.** `db/migration/postgresql` and `db/migration/h2`, identical file names
(enforced by `LegacyDatabaseMigrationTest.everyMigrationExistsForBothVendors`). `baseline-on-migrate=true`,
`baseline-version=1`: existing databases skip V1 (the dumped pre-v1 schema) and get V2+. Verified on PostgreSQL 17 +
pgvector with a legacy-shaped DB and on a fresh DB. Hibernate schema management is `none` (tests: `validate`).

**D-004 Migration version ranges per worker** (V10–19 W1a, V20–29 W1d, V30–39 W1e, V40–49 W2a, V50–59 W2b,
V60–69 W3a, V70–79 W3b, V80–89 W3c) so parallel branches never collide.

**D-005 Config validated before migration.** `quarkus.flyway.migrate-at-start=false` (prod); `StartupValidation`
checks profile/providers/consent/DB password, reports *all* problems in one message, then runs Flyway.

**D-006 Provider selection via CDI qualifier.** Implementations are `@ApplicationScoped @ProviderName("x")
@Unremovable`; `ProviderProducers` exposes the unqualified interface chosen by `journal.<cap>.provider`. Classes that
send data off-device implement `ExternalProvider` and are rejected unless `journal.profile=lite-cloud`.

**D-007 lite-cloud is a separate build.** Datasource kind is build-time in Quarkus, so the Pi artifact is built with
`-Dquarkus.profile=lite-cloud` (H2 file DB) and run with `QUARKUS_PROFILE=lite-cloud`. Startup requires
`JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE`, `OPENAI_API_KEY` and an H2 build. Verified manually.

**D-008 Keep the two-JVM layout on the Pi (for now).** Spike (docs/PI.md): ~160 + ~172 MB RSS on x86, ≤ 314 + 347 MB
under arm64 *emulation* (upper bound incl. QEMU overhead). Fits the 750 MB budget. Re-measure on real hardware in
Wave 4; fallback if it does not fit: single-process packaging (frontend embeds a backend client in-process) — would
be recorded as a new decision.

**D-009 Added worker W1e (LLM, embedding, vector providers).** The brief assigns no wave to Ollama/OpenAI chat,
embeddings and the pgvector/jdbc vector stores, but W2/W3 need them. quarkus-langchain4j extensions were removed in
Wave 0 (their build-time model wiring conflicts with runtime provider choice); W1e implements gateways over plain
HTTP or langchain4j core modules with programmatic builders.

**D-010 Browser talks only to the frontend.** Backend CORS disabled; audio chunks go through a same-origin proxy in the
frontend (W1b). Backend and frontend bind 127.0.0.1 by default (`JOURNAL_HTTP_HOST`), compose publishes on 127.0.0.1.

**D-011 Legacy data kept, not migrated into new structures.** `documents` stays the entries table (columns added);
old `aicontent` is exposed read-only as `legacyAiRemark`. `chat_history` and pgvector `diary` are no longer written
but are covered by export and delete-all (`LegacyDataOwner`). Re-embedding old entries is W1e's backfill.

**D-012 New vectors in a new table.** Vectors carry a `modelId`; search filters on it, so a model change never mixes
embedding spaces. pgvector store uses an unbounded `vector` column (no ANN index needed at journal scale).

**D-013 JS tests with `node --test`.** Zero npm dependencies, runs offline. Pure modules live in
`frontend/src/main/frontend/**`, tests in `frontend/src/test/js/**/*.test.mjs`.

**D-014 Commit trailer.** `Assisted-by: Claude` per owner instruction; no `Co-authored-by` for Claude.

**D-015 OLLAMA_DEBUG removed** from compose (debug logging can include prompts = journal content).

**D-016 Unknown `journal.*` keys allowed.** `JournalConfig` maps prefix `journal`, which made SmallRye reject provider
keys set in properties files. `smallrye.config.mapping.validate-unknown=false` instead of per-worker registration
mappings (W1e's `W1eConfigKeys` stays as documentation of its keys). Typos in provider keys are therefore not caught by
SmallRye; `SelfValidating` providers check their own values.

**D-017 Merge order Wave 1:** W1e, W1c merged first (independent); W1a, W1d, W1b follow.
