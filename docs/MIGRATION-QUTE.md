# Migration plan: Vaadin frontend → Qute pages in the backend

Status: **proposed** (2026-11-24); phase 0 implemented on branch `qute/phase-0`. Decision record: D-033 in [DECISIONS.md](DECISIONS.md).
Proof of concept: branch `spike/qute-journal` (Journal page, commit `bd72455`).

## Why
The Raspberry Pi 3 profile has 750 MB for all Java processes (SPEC section 13). Today two JVMs run: the Quarkus
backend and the Vaadin/Spring Boot frontend. Measured on 2026-11-24 with the Pi JVM flags, fake AI and a throwaway
database (Windows working set, peak, which is higher than Linux RSS):

| Process | Peak |
|---|---|
| backend, Quarkus + the Qute Journal page | 210 MB |
| frontend, Vaadin 24.9 + Spring Boot 3.5 (production build) | 180 MB |

Removing the frontend JVM saves about **180 MB (≈ 46 %)**, one systemd unit, one Docker image, the `/upload` proxy hop,
the REST client layer and a second set of security filters. It also ends the pending Vaadin 25 / Spring Boot 4 / Jackson 3
migration (Dependabot PRs for both stay unmerged and are closed when the frontend is removed).

## Target architecture
```
browser ──same origin──> backend (Quarkus, :8080)
                          ├─ HTML pages: Qute templates (quarkus-rest-qute), progressive enhancement
                          ├─ static: /css, /js, /icons (META-INF/resources)
                          ├─ /api/** JSON (unchanged contract)
                          └─ SPI providers ──> Ollama / Whisper / Piper | OpenAI (lite-cloud only)
```
- Pages are server-rendered and work without JavaScript where it is reasonable (reading, navigation, plain forms).
  Interactive parts are small vanilla ES modules (no framework, no CDN, no inline script; SPEC 10).
- Page classes call the services directly (no HTTP hop). Pure view logic lives in small presenter classes, unit-tested
  like the Vaadin presenters today (`JournalPresenter` in the spike is the pattern).
- Look and accessibility stay as they are: the "Quiet paper" tokens, building blocks, page stylesheets and icon sprite
  move over; `ThemeContrastTest` moves to the backend. Lumo mappings disappear; native controls get explicit styles.

## What carries over unchanged
- **JavaScript custom elements** (checked: no Vaadin coupling, URLs are attributes): `recorder/*` (`diary-recorder`,
  chunk queue, upload API, retry, resume, IndexedDB storage, state machine), `coach/*` (`diary-answer-recorder`,
  `diary-coach-speech`, playback queue, recording and page guards), `entries/audio-player.js`, `lib/backoff.js`, and
  their 94 JS tests. Only the default URLs change from `/upload/...` to the backend's `/api/...` paths.
- CSS: `themes/diary/styles.css` (minus Lumo), `today/`, `entries/`, `coach/`, `memory/`, `gym/`, `weekly/`,
  `settings/` stylesheets; `theme-init.js`; `diary-icons.svg`.
- i18n texts: all `i18n/*_{en,de}.properties` keys, read by a small `ResourceBundle` helper (spike: `Messages`).
- The JSON API and its tests.

## What has to be rebuilt
| Area | Vaadin today | Qute replacement |
|---|---|---|
| Shell | `MainLayout`, `CloudBanner`, `NavDestination` | `base.html` layout + `Shell` model (spike), offline banner via a small status script |
| Form controls | `vaadin-text-area`, `select`, `checkbox`, `integer-field`, `date-picker` | native `<textarea>`, `<select>`, `<input>` with the existing token styles |
| Dialogs | `ConfirmDialog` | native `<dialog>` + `confirm.js`; destructive actions keep their no-JS fallback page |
| Notifications | `Notification` | one `aria-live` region + `notify.js` |
| Autosave | `AutosaveEditor`/`AutosavePresenter` (server push) | `autosave.js`: debounced `PUT /api/entries/{id}`, same states and texts |
| Polling | `TranscriptionPolling` | `poll.js` with `lib/backoff.js` against `/api/transcriptions/...` |
| Coach | `CoachView`/`CoachPresenter` | page renders the conversation; `coach.js` drives answers against `/api/coach/...` |
| Settings | `SettingsView` | plain form POST → redirect → flash message; theme switch stays client-side |
| Export | `ExportDownload` stream | direct link to the existing export endpoint |
| Locale, time zone | `UiLocaleResolver`, `BrowserZone`/`ClientTime` | `Accept-Language` + setting; browser zone sent once by a script and kept in a cookie |

## Security changes (must land before the first page goes live)
1. **Same-origin requests**: `RequestGuardFilter` rejects any `Origin` outside `journal.http.allowed-origins`. The
   backend's own origin must be accepted automatically (derived from the allowed hosts and port).
2. **CSRF for writes**: keep the rule "every non-GET needs `X-AI-Diary: 1`". All writes go through `fetch` (which can
   set the header). Plain HTML form POSTs (no-JS fallback) get a per-session synchronizer token instead; no write
   endpoint accepts neither.
3. **Host guard and binding**: the backend now faces the browser. Keep `127.0.0.1` as the default bind; LAN access
   (D-031) moves its settings (`JOURNAL_ALLOWED_HOSTS`, bind address) from the frontend to the backend.
4. **Headers**: CSP `default-src 'self'` without inline script/style, `nosniff`, `no-referrer`, `frame-ancestors 'none'`
   and `Cache-Control: no-store` on HTML and on `/api` responses that carry journal content (spike: HTML only).
5. **Swagger/OpenAPI**: off in prod and lite profiles.
6. **No login** (unchanged threat model: single user, local device). Document that LAN mode exposes the journal to
   the LAN, as today, and recommend leaving it off.
7. Re-run the security review checklist from Wave 4 against the new surface.

## Phases
Each phase is one branch, merged when `verify` and the smoke tests pass. The Vaadin frontend keeps working until
phase 5, so the app is usable at every step. Phases 2–4 can run in parallel (separate files).

| # | Scope | Done when |
|---|---|---|
| 0 | Merge the spike; security items 1, 4, 5; move `ThemeContrastTest`; shared JS (`notify`, `confirm`, `autosave`, `poll`) | backend serves `/journal` behind the guard; unit + JS tests green |
| 1 | Today (composer, recorder element, review panel, timeline) + entry detail + audio player | voice and text entry end to end in the Qute UI |
| 2 | Journal search/filter (new `GET /api/entries?q=&source=&reflected=`) | open item from the UI overhaul closed |
| 3 | Coach (Reflect) — and here the side-by-side layout from the mockup becomes cheap | coach flow incl. voice answers and TTS |
| 4 | Remember (with next-interval hints: extend `ReviewCardDto`), Gym, Week, Settings (+ export, delete-all) | every Vaadin route has a Qute page |
| 5 | Remove `frontend/` module, its Dockerfile, systemd unit, compose service, scripts; port 8081 → 8080 in docs; close Dependabot PRs #20 and #26 | one JVM; `smoke-plain`, `smoke-lite`, `smoke-compose` green; DESIGN/PI/README/CONFIG updated |
| 6 | Measure on a real Pi 3 (RSS of the one JVM), re-tune `-Xmx`; a11y pass (keyboard, screen reader, axe) | numbers in PI.md; A11Y.md updated |

## Conventions (phase 0 foundation; every later phase follows them)
**Routes.** `/` Today, `/journal` list, `/journal/{id}` entry detail, `/journal/{id}/reflect` coach, `/memory`, `/gym`,
`/weekly`, `/settings`. `/api/**` stays JSON. Routes not built yet answer with the "not moved yet" page
(`NotMovedResource`, plus the two `/journal/{id}` methods in `JournalResource`): a phase deletes the method of the route
it builds. One URL prefix belongs to one resource class (JAX-RS), so nested routes live in the prefix's class.

**Java.**
- One resource per page under `web/ui/`, with a typed `@CheckedTemplate` (`static native TemplateInstance page(Shell shell, Messages t, <ViewModel> v)`),
  template at `templates/<Resource>/<method>.html`, layout `{#include base}`. Fragments (`{#fragment id=...}`) for the
  progressive-enhancement chunks.
- Presenters are plain Java (no Quarkus/JAX-RS types), pure, with unit tests (`JournalPresenter` is the pattern).
  Resources only gather data, call a presenter, and build the view model.
- "Today/Yesterday" and other date logic take the zone from the `diary-tz` cookie: `ClientZone.resolve(cookie, clock.getZone())`.
- Shell: `ShellFactory.create(messages, activeHref)`; the cloud status comes from `SystemInfoService` (shared with `/api/system/info`).
- Writes without JavaScript: a `POST` form route consumes `application/x-www-form-urlencoded`, declares
  `@FormParam("_csrf") String token`, calls `csrf.require(token)` (injected `Csrf`) before changing anything, and answers
  `303` to a GET page (flash message via query parameter, never journal text). `CsrfCookieFilter` refuses a form route
  that does not declare `_csrf`. Templates emit the field with `{#csrf /}` inside the `<form method="post">`.
  `TestFormResource` (test sources) is the reference implementation.

**Templates and HTML.** Qute auto-escaping always; never `raw`/`safe` on user or model text. No inline `<script>`,
`style=""` or `on*=` (CSP `script-src 'self'; style-src 'self'`). One `h1` per page; every control labelled; native
elements first (`<button>`, `<a>`, `<dialog>`, `<details>`); after an in-place update focus goes to the new content or
a status; status text lives in an `aria-live="polite"` region (`role="status"`); focus is trapped only in a modal
`<dialog>`. Calm UI: no streaks, scores, alarm colours for ordinary states.

**i18n.** Texts come from `Messages` (`i18n/<feature>_{en,de}.properties`, copied from the frontend, CRLF line endings,
non-ASCII characters as Unicode escapes like the frontend files); a new feature's bundle is added to `Messages.BUNDLES`.
Language: `Accept-Language`, later the setting. Texts needed by JavaScript are rendered into `data-*` attributes of the
element; the modules contain no texts of their own.

**CSS.** Shared tokens and building blocks in `css/styles.css` (contrast verified by `ThemeContrastTest`); one stylesheet
per page `css/<page>.css`, loaded via `{#head}`, classes prefixed with the page (`entries-`, `coach-`, `memory-`, ...).

**JavaScript.** One ES module per page `js/<page>.js` (`<script type="module" src="/js/<page>.js">` in `{#scripts}`),
importing the shared modules below. `js/shell.js` is on every page (time zone cookie, offline banner). Same-origin
`fetch` only, via `api.js` (always `X-AI-Diary: 1`). Forms: the JS path intercepts `submit`, calls the API with `api.js`
and updates the page; the no-JS path is the real `<form method="post">` with `{#csrf /}`.

| Module | API |
|---|---|
| `api.js` | `api(path, {method, body, headers, signal})`, `get/post/put/del(path, ...)` resolve parsed JSON (or `null`); reject with `ApiError {code, message, status}` (`NETWORK`, `HTTP_<n>`, or the backend's `ErrorDto.code`) |
| `notify.js` | `notify(text, {kind: 'info' or 'error', timeoutMs})` into `#diary-notify` |
| `confirm.js` | `confirmDialog({title, message, confirmLabel, cancelLabel, destructive}) -> Promise<boolean>` |
| `autosave.js` | `AutosavePresenter`, `entryOperations()`, `createAutosave({presenter, getText, create, update, onState, onSaved})`, `bindAutosave({textarea, status, presenter, ops, onSaved})`; the status element carries `data-saving/-saved/-failed/-retry` |
| `poll.js` | `createPoller({task, isDone, onResult, onError, intervalMs: 2000, maxPolls, baseMs, maxMs}) -> {start, stop, running}` |
| `status.js` | `initStatus()` (done by `shell.js`); `createStatus({banner, navStatus, fetchInfo}).check()` |
| `tz.js` | `ensureTimeZoneCookie()` (done by `shell.js`) |
| `lib/backoff.js` | `retryDelayMs(attempt, {baseMs, maxMs, random})` (copy of the frontend file; identical until phase 5) |

**Tests.** Java: unit tests for presenters, `@QuarkusTest` page tests with RestAssured (structure, escaping, one `h1`,
headers, de/en). Browser modules: `node --test` with injected fakes (`backend/src/test/js/*.test.mjs`, no dependencies;
`backend/package.json` only marks the folder as ES modules). Run them with `cd backend && npm test` or
`node --test "backend/src/test/js/**/*.test.mjs"` from the repository root; `scripts/verify.*` runs them next to the
frontend's. `ThemeContrastTest` (backend) parses the tokens of `css/styles.css`; new tokens get a contrast assertion.

## Effort estimate
The spike (one read-only page with progressive enhancement, tests, security headers) took one agent about 90 minutes.
Interactive pages are 2–3× that. Rough total: phases 0–4 about 2–3 working days of agent time with review, phase 5
half a day, phase 6 needs the hardware. The 5,700 lines of Vaadin view code shrink, because most of it is component
wiring that templates replace.

## Risks
- **Two UIs for a while** (phases 1–4): both stay fully working on the same API and database; the default entry point
  switches in phase 5 only, so there is never a half-migrated UI.
- **Behaviour drift** in autosave, recording recovery and coach guards: port their existing tests first, then the code.
- **Accessibility regressions** from replacing Vaadin components: each page keeps one `h1`, labelled controls, focus
  management and `aria-live` statuses; the A11Y checklist runs per phase.
- **Quarkus Qute quirks** (method resolution in templates): typed templates (`@CheckedTemplate`) catch them at build time.
