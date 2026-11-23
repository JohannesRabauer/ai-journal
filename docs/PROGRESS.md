# Progress

## Wave 0 — done (2026-11-23), waiting for spec approval before Wave 1
| Item | Status | Evidence |
|---|---|---|
| Spec | Drafted (docs/SPEC.md v0.1) | owner review pending |
| Pi memory spike | Done | docs/PI.md (332 MB x86, ≤ 661 MB arm64-emulated) |
| Flyway baseline | Done | V1 = dumped pre-v1 schema, V2 lifecycle+settings; legacy PG DB upgrade verified manually; `LegacyDatabaseMigrationTest` (H2) |
| Contracts | Done | `api/` DTOs, `rest/*Api` + 501 stubs, `spi/*` + fakes, abstract contract tests |
| Provider config + validation | Done | `ConfigValidatorTest`, manual misconfig start shows all problems |
| Hardening | Done | save-first `/api/entries`, newest-first paging, no session id, CORS off, no content logging, `.env`, 127.0.0.1 binding, no DB trust for new DBs |
| Stoic auto-analysis | Removed | `EntryResourceTest.entryIsPersistedImmediatelyWithoutAnyAiCall` |
| Verify scripts | Done | `scripts/verify.ps1` green: 62 backend, 7 frontend, 3 JS tests |

Not verified in Wave 0: the interim Vaadin view in a browser (replaced in W1c); full compose stack start.

## Open questions for the owner
1. Approve or amend docs/SPEC.md (especially §5 rule 7 crisis message, §6 lesson list, §7 categories, §8 sections).
2. Existing database `db/data` was initialized with `trust` auth; v1 docs will describe switching it to a password.

## Wave 1 — not started
