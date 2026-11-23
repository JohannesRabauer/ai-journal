# Progress

## Wave 0 — done (2026-11-23); spec approved by the owner the same day
| Item | Status | Evidence |
|---|---|---|
| Spec | docs/SPEC.md v0.1 | approved by the owner |
| Pi memory spike | Done | docs/PI.md (332 MB x86, ≤ 661 MB arm64-emulated) |
| Flyway baseline | Done | V1 = dumped pre-v1 schema, V2 lifecycle+settings; legacy PG DB upgrade verified manually; `LegacyDatabaseMigrationTest` (H2) |
| Contracts | Done | `api/` DTOs, `rest/*Api` + 501 stubs, `spi/*` + fakes, abstract contract tests |
| Provider config + validation | Done | `ConfigValidatorTest`, manual misconfig start shows all problems |
| Hardening | Done | save-first `/api/entries`, newest-first paging, no session id, CORS off, no content logging, `.env`, 127.0.0.1 binding, no DB trust for new DBs |
| Stoic auto-analysis | Removed | `EntryResourceTest.entryIsPersistedImmediatelyWithoutAnyAiCall` |
| Verify scripts | Done | `scripts/verify.ps1` green: 62 backend, 7 frontend, 3 JS tests |

Not verified in Wave 0: the interim Vaadin view in a browser (replaced in W1c); full compose stack start.

## Open questions for the owner
1. Existing database `db/data` was initialized with `trust` auth; v1 docs will describe switching it to a password.

## Wave 1 — in progress (started 2026-11-23)
| Worker | Branch | Worktree | Status |
|---|---|---|---|
| W1a STT, recordings, audio storage | feat/w1a-stt | ../wt-w1a | merged (173 backend tests on main) |
| W1b recorder + entries UI | feat/w1b-recorder-ui | ../wt-w1b | running |
| W1c design shell | feat/w1c-shell | ../wt-w1c | merged (29 frontend tests; browser-checked against a mock backend) |
| W1d deployment + privacy | feat/w1d-deploy | ../wt-w1d | running |
| W1e LLM/embedding/vector providers | feat/w1e-providers | ../wt-w1e | merged (120 backend tests; opt-in pgvector contract run green on PG 17) |

Orchestrator fixes after merge: D-016 (journal.* keys), D-018 route contract, D-019 delete cascade, D-020 429 retryable.
