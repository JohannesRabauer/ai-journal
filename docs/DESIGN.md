# Design notes (owner: W1c)

Direction "Quiet paper": calm, text-first, a warm paper background with raised cards, one accent colour, generous
whitespace. System sans-serif fonts only: no external fonts or CDNs (SPEC section 10) and, by owner decision, no serif
face. Implemented in `backend/src/main/resources/META-INF/resources/css/styles.css` (shared tokens and building blocks; one stylesheet per page next to it) with native HTML controls, no UI framework (D-033). A clickable mockup of
the full overhaul is in `docs/mockups/ui-overhaul.html`.

## Principles
- Reading measure about 65ch (`--diary-measure`); content column is centred.
- Typography: `system-ui` stack, base size 17px, line height 1.6; headings weight 650 with slight negative tracking.
- Shape: cards use `--diary-radius-lg` (1.25rem) and `--diary-shadow`; controls use `--diary-radius-md`; buttons are
  pills.
- Icons: thin line icons from the local sprite `META-INF/resources/icons/diary-icons.svg`, created with `DiaryIcons`.
  They are decorative (`aria-hidden`) and always sit next to a text label or an `aria-label`.
- Colour is never the only signal: the active navigation item is a raised card with a bar marker plus bold text, errors
  carry the text prefix "Error:" and a border, the cloud banner has a double rule and text.
- Motion: only a 150 ms hover transition; `prefers-reduced-motion: reduce` disables all transitions and animations.
- No streaks, counters or scores in the shell.

## Colour scheme
Follows `prefers-color-scheme`. Manual override in Settings (system / light / dark), stored in the browser's
`localStorage` key `diary.theme` and applied by `js/theme-init.js` before first render (the `theme="dark"`
attribute on `<html>`).

## Tokens and contrast (WCAG 2.1 AA)
Ratios computed with the WCAG relative luminance formula. `ThemeContrastTest` parses the tokens from `css/styles.css` and
fails the build if any pair drops below its threshold (4.5:1 for text, 3:1 for control borders and focus ring).

| Token | Light | Dark | Use |
|---|---|---|---|
| `--diary-bg` | #f6f3ed | #14171a | page background (paper) |
| `--diary-surface` | #fffdf9 | #1c2024 | cards, active nav item, banner, nav bar (mobile) |
| `--diary-surface-2` | #efebe3 | #23282d | recessed areas: segmented controls, wells |
| `--diary-text` | #1f2328 | #e8e6e3 | body text |
| `--diary-muted` | #575d64 | #a9afb5 | secondary text |
| `--diary-accent` | #2c5f66 | #7cc4cc | the one accent: links, primary buttons, focus ring, active marker |
| `--diary-accent-soft` | #dfeae9 | #1f3336 | accent chip fill ("Reflection"), coach bubbles |
| `--diary-on-accent` | #ffffff | #10181a | text on accent fills |
| `--diary-sage` / `--diary-sage-text` | #e6ebe1 / #3d4d38 | #262d24 / #c5d4bd | neutral chip ("Voice") |
| `--diary-error` | #a4262c | #ff9a9f | error text and borders |
| `--diary-border` | #83888d | #7b8288 | control borders |
| `--diary-rule` | #e3ddd2 | #2d3338 | dividers, card outlines (decorative) |

| Pair | Light | Dark | Required |
|---|---|---|---|
| text on bg / surface / surface-2 | 14.26 / 15.55 / 13.29 | 14.44 / 13.16 / 11.93 | 4.5 |
| muted on bg / surface / surface-2 | 6.01 / 6.55 / 5.60 | 8.13 / 7.40 / 6.71 | 4.5 |
| accent on bg / surface | 6.46 / 7.04 | 9.10 / 8.29 | 4.5 |
| on-accent on accent | 7.15 | 9.10 | 4.5 |
| accent on accent-soft | 5.82 | 6.70 | 4.5 |
| sage-text on sage | 7.48 | 9.12 | 4.5 |
| error on bg / surface | 6.56 / 7.15 | 8.89 / 8.10 | 4.5 |
| border on bg / surface | 3.23 / 3.52 | 4.62 / 4.21 | 3.0 |

## Pages
Each page has its own stylesheet, loaded with `@CssImport` and prefixed by feature (`today/today.css`,
`entries/entries.css`, `coach/coach.css`, `memory/memory.css`, `gym/gym.css`, `weekly/weekly.css`,
`settings/settings.css`). They use only `--diary-*` tokens and the shared building blocks (`.diary-card`,
`.diary-chip`, `.diary-eyebrow`). Today: Speak / Write composer and a timeline of today's entries (no AI output).
Journal: entries grouped by day, "Load older entries". Entry: reading column, custom audio player
(`entries/audio-player.js`), Reflect card linking to the coach chat. Remember: one flashcard at a time.
Week: article layout with week arrows. Gym: category tiles. Settings: grouped cards, segmented theme control.

## Layout and navigation
One `<nav aria-label>` landmark, restyled by CSS. At 768px and wider it is a side bar: brand mark, the destinations,
Settings set apart by a rule, and a quiet privacy line at the bottom ("Local. Nothing leaves this device.", or the cloud
/ offline state, mirrored from the banner). Below 768px it is a fixed bottom bar with five items (Today, Journal,
Remember, Thinking Gym, Week); Settings moves to a gear link in a small header with the brand. Items follow SPEC
section 12, each with icon and text label; the active item has `aria-current="page"`. A skip link ("Skip to main
content") is the first focusable element; content lives in `<main id="main-content" tabindex="-1">`.

The cloud / offline banner sits above `<main>` in the shell, so it appears on every route, is sticky and cannot be
dismissed. It is hidden in local profiles.

## i18n
`DiaryI18NProvider` merges `i18n/<feature>_<lang>.properties` (shell, settings, today, recorder, entries, coach, memory,
gym, weekly; missing files are fine), fallback en, then the key. Keys are prefixed by feature. UI locale comes from
`settings.uiLanguage` unless AUTO, else the browser locale.

## Accessibility additions (W4b, details in docs/A11Y.md)
Focus rings use the solid accent (`ThemeContrastTest.focusRingIsTheSolidAccent`); form controls get a 1px `--diary-border` boundary; the error button colours are covered by `ThemeContrastTest` (`backend/src/test/java/dev/rabauer/ai/diary/web/ui/`). Every page has one `h1`.
