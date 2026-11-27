# Design notes (owner: W1c)

Direction "Dusk": a diary lives in days, so the page is a quiet sky. Two soft glows sit behind everything and follow
the local time of day (morning apricot and rose, day blue, evening violet and ember, night indigo and teal). Text is
ink indigo on a cool mist background (light) or a deep night blue (dark), with one iris accent and an ember second hue
for voice. The one loud element is the record button on Today: a lit orb with a halo. Everything else stays quiet so
the writing keeps the attention. System sans-serif fonts only: no external fonts or CDNs (SPEC section 10) and, by
owner decision, no serif face. Implemented in `backend/src/main/resources/META-INF/resources/css/styles.css` (shared
tokens and building blocks; one stylesheet per page next to it) with native HTML controls, no UI framework (D-033).
The older mockup in `docs/mockups/ui-overhaul.html` still shows the previous "Quiet paper" colours; the layout is the same.

## Principles
- Reading measure about 65ch (`--diary-measure`); content column is centred.
- Typography: `system-ui` stack, base size 17px, line height 1.6. Headings use the display cut of the system font
  where there is one (`--diary-font-display`: Segoe UI Variable Display, SF Pro Display), weight 650, tight negative
  tracking. The greeting on Today is the largest line of the app. The small line above a heading (`.diary-eyebrow`:
  date, count) is plain sentence case.
- Shape: cards use `--diary-radius-lg` (1.25rem) and `--diary-shadow` (`--diary-shadow-lift` on hover and for the
  composer, dialog and notifications); controls use `--diary-radius-md`; buttons are pills. The composer on Today has a
  larger radius and a hairline edge that picks up the two sky colours.
- Sky: `body::before`, fixed, two radial glows from `--diary-sky-a` / `--diary-sky-b` at `--diary-sky-strength`.
  `js/theme-init.js` sets `data-daypart` (`morning` 5-11, `day` 11-17, `evening` 17-22, `night`) on `<html>`; without
  JavaScript the evening colours apply. The sky is decoration: no text sits on a sky colour, and the glows are faint
  enough that the contrast ratios below still hold on top of them. The side bar, the sticky day headings and the
  banner are frosted (`backdrop-filter`) so the sky shows through.
- Icons: thin line icons from the local sprite `META-INF/resources/icons/diary-icons.svg`.
  They are decorative (`aria-hidden`) and always sit next to a text label or an `aria-label`.
- Colour is never the only signal: the active navigation item is a raised card with a bar marker plus bold text, errors
  carry the text prefix "Error:" and a border, the cloud banner has a double rule and text.
- Motion, all of it switched off by `prefers-reduced-motion: reduce`: pages cross-fade (cross-document view
  transitions, 180 ms) and the active navigation marker glides to its new place; the record orb's halo breathes slowly
  while it waits and ripples while it records; cards lift 1px on hover; buttons press in slightly. Nothing else moves.
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
| `--diary-bg` | #f3f3fa | #0e0f1f | page background (mist / night) |
| `--diary-surface` | #ffffff | #17182d | cards, active nav item, banner, nav bar (mobile) |
| `--diary-surface-2` | #e8e8f4 | #20223b | recessed areas: segmented controls, wells |
| `--diary-text` | #191a2e | #ecebf7 | body text (ink) |
| `--diary-muted` | #55587a | #a8abc8 | secondary text |
| `--diary-accent` | #4a3fd0 | #aca0ff | the one accent (iris): links, primary buttons, focus ring, active marker |
| `--diary-accent-soft` | #e7e5fc | #29265a | accent chip fill ("Reflection"), coach bubbles, soft buttons |
| `--diary-on-accent` | #ffffff | #131129 | text on accent fills |
| `--diary-sage` / `--diary-sage-text` | #fbecd8 / #7a4712 | #35291a / #f3c98f | ember chip ("Voice"); the token keeps its old name |
| `--diary-error` / `--diary-on-error` | #b3261e / #ffffff | #ffa39c / #1d0f10 | error text and borders, destructive buttons, stop button |
| `--diary-border` | #7d809c | #7a7ea0 | control borders |
| `--diary-rule` | #dedfee | #292c48 | dividers, card outlines (decorative) |

| Pair | Light | Dark | Required |
|---|---|---|---|
| text on bg / surface / surface-2 | 15.47 / 17.09 / 14.07 | 16.08 / 14.77 / 13.15 | 4.5 |
| muted on bg / surface / surface-2 | 6.21 / 6.86 / 5.64 | 8.42 / 7.74 / 6.89 | 4.5 |
| accent on bg / surface | 6.53 / 7.21 | 8.34 / 7.66 | 4.5 |
| on-accent on accent | 7.21 | 8.09 | 4.5 |
| accent on accent-soft | 5.84 | 6.07 | 4.5 |
| sage-text on sage | 6.61 | 9.14 | 4.5 |
| error on bg / surface | 5.92 / 6.54 | 9.91 / 9.10 | 4.5 |
| on-error on error | 6.54 | 9.73 | 4.5 |
| border on bg / surface | 3.49 / 3.86 | 4.81 / 4.41 | 3.0 |

## Pages
Each page has its own stylesheet, loaded with `@CssImport` and prefixed by feature (`today/today.css`,
`entries/entries.css`, `coach/coach.css`, `memory/memory.css`, `gym/gym.css`, `weekly/weekly.css`,
`settings/settings.css`). They use only `--diary-*` tokens and the shared building blocks (`.diary-card`,
`.diary-chip`, `.diary-eyebrow`). Today: greeting, Speak / Write composer (record orb or a large writing area) and a timeline of today's entries (no AI output).
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
