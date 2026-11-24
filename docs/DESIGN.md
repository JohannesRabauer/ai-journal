# Design notes (owner: W1c)

Calm, minimalist, text-first. One accent colour, generous whitespace, system fonts only (no external fonts or CDNs,
SPEC section 10). Implemented in `frontend/src/main/frontend/themes/diary/styles.css` on top of Vaadin Lumo.

## Principles
- Reading measure about 65ch (`--diary-measure`); content column is centred.
- Typography: `system-ui` stack, base size 17px, line height 1.6.
- Colour is never the only signal: the active navigation item has a bar marker plus bold text, errors carry the text
  prefix "Error:" and a border, the cloud banner has a double rule and text.
- Motion: only a 150 ms hover transition; `prefers-reduced-motion: reduce` disables all transitions and animations.
- No streaks, counters or scores in the shell.

## Colour scheme
Follows `prefers-color-scheme`. Manual override in Settings (system / light / dark), stored in the browser's
`localStorage` key `diary.theme` and applied by `themes/diary/theme-init.js` before first render (Lumo's `theme="dark"`
attribute on `<html>`).

## Tokens and contrast (WCAG 2.1 AA)
Ratios computed with the WCAG relative luminance formula. `ThemeContrastTest` parses the tokens from `styles.css` and
fails the build if any pair drops below its threshold (4.5:1 for text, 3:1 for control borders and focus ring).

| Token | Light | Dark | Use |
|---|---|---|---|
| `--diary-bg` | #faf8f5 | #16191c | page background |
| `--diary-surface` | #ffffff | #1e2226 | banner, nav bar (mobile), status boxes |
| `--diary-text` | #1f2328 | #e8e6e3 | body text |
| `--diary-muted` | #575d64 | #a9afb5 | secondary text |
| `--diary-accent` | #2c5f66 | #7cc4cc | the one accent: links, primary buttons, focus ring, active marker |
| `--diary-on-accent` | #ffffff | #10181a | text on accent fills |
| `--diary-error` | #a4262c | #ff9a9f | error text and borders |
| `--diary-border` | #8a8f94 | #7b8288 | control borders |

| Pair | Light | Dark | Required |
|---|---|---|---|
| text on bg | 14.90 | 14.17 | 4.5 |
| text on surface | 15.80 | 12.85 | 4.5 |
| muted on bg | 6.28 | 7.97 | 4.5 |
| muted on surface | 6.66 | 7.23 | 4.5 |
| accent on bg | 6.75 | 8.93 | 4.5 |
| accent on surface | 7.15 | 8.10 | 4.5 |
| on-accent on accent | 7.15 | 9.10 | 4.5 |
| error on bg | 6.85 | 8.72 | 4.5 |
| error on surface | 7.26 | 7.91 | 4.5 |
| border on bg | 3.08 | 4.53 | 3.0 |
| border on surface | 3.26 | 4.11 | 3.0 |

## Layout and navigation
One `<nav aria-label>` landmark, restyled by CSS: side bar at 768px and wider, fixed bottom bar below. Items follow
SPEC section 12 (Today, Journal, Remember, Thinking Gym, Week, Settings), each with icon and text label; the active item
has `aria-current="page"`. A skip link ("Skip to main content") is the first focusable element; content lives in
`<main id="main-content" tabindex="-1">`.

The cloud / offline banner sits above `<main>` in the shell, so it appears on every route, is sticky and cannot be
dismissed. It is hidden in local profiles.

## i18n
`DiaryI18NProvider` merges `i18n/<feature>_<lang>.properties` (shell, settings, today, recorder, entries, coach, memory,
gym, weekly; missing files are fine), fallback en, then the key. Keys are prefixed by feature. UI locale comes from
`settings.uiLanguage` unless AUTO, else the browser locale.

## Accessibility additions (W4b, details in docs/A11Y.md)
Focus rings use the solid accent (`--lumo-primary-color-50pct` is redefined); form controls get a 1px `--diary-border` boundary; `--lumo-error-contrast-color` on `--diary-error` is covered by `ThemeContrastTest`. Every page has one `h1`.
