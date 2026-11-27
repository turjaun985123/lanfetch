# LanFetch — Responsive Design

> Status: staged draft for V2 Wave 9. This is the durable reference
> distilled from `desktop-tauri/DESIGN.md` (the redesign contract) and
> `desktop-tauri/RESPONSIVE-QA.md` (the human verification checklist).
> Where this document and DESIGN.md disagree, DESIGN.md wins until
> this distillation is adopted into `docs/`.

One shell serves every surface: the Tauri desktop window and the web
edition in a phone/tablet/desktop browser. There are not two layouts
to keep in sync — there is one `#rail`/`#nav` element and one set of
screens, restyled by band.

## 1. The bands

| Band | Width | Navigation | Poster grid minimum | Notes |
|---|---|---|---|---|
| Phone | <768px | Fixed bottom nav (4 items + badge), `env(safe-area-inset-bottom)` padding | 104px (2 cols @320, 3 @430) | Headers stack; Detail stacks (poster 128px); settings labels stack |
| Tablet | 768–1023px | Compact icon rail, 64px, labels hidden (tooltips via `title`) | 140px | Rail is *forced* compact in this band |
| Desktop | ≥1024px | Expanded rail, 208px, user-collapsible to 64px | 148→176px by band | Collapse choice persisted in `localStorage`; content caps at 2000px (grid) / 1600px (text screens), centered |

Rules that hold the bands together:

- **One element, all bands.** The single `#rail`/`#nav` restyles into
  the bottom bar below 768px. Compact is keyed off
  `html[data-rail="compact"]`, set pre-paint and kept truthful on
  resize: forced at 768–1023px, the persisted user choice at ≥1024px.
  The collapse toggle lives in the brand row; in compact it stacks
  under the glyph, icon flipped.
- **The desktop app never enters the phone layout.** The Tauri
  window's minimum is 768×560 (`minWidth` 768, `minHeight` 560;
  default 1280×800), chosen so the window cannot cross into the
  <768px band. Web mode has no such floor and uses all three bands.
- **Settings two-pane starts at ≥1280px** (section nav + panel);
  below that, sections stack with the nav as a row.
- **Touch:** `@media (pointer: coarse)` raises chips, buttons and
  nav items to ≥40px targets.
- Screen padding 24px desktop / 16px mobile; grid gap 16×20 desktop /
  12×16 mobile; card padding 16 / 12 mobile. Spacing is a 4px base —
  off-scale values (10px, 14px gaps) are banned in new work.

## 2. Tokens — the only way to style

- **All design tokens live in `src/styles/global.css`** — the single
  source. No screen, component, or module defines its own colors,
  shadows, radii, or durations. If a value isn't a token, it doesn't
  ship.
- Identity: *the chrome is quiet, precise, and neutral — the posters
  are the only color in the room.* Accent is indigo `#2b39f0` (light)
  / `#8e98ff` (dark), reserved for primary actions, active
  indicators, focus, and progress — never decoration, never a
  background wash, never body text, never accent-colored brand text.
- **Contrast law:** filled accent buttons take `--on-accent` text —
  white on the light accent (7.13:1), `#12141f` on the dark accent
  (7.23:1). White text on the dark accent is 2.60:1 and is
  **forbidden** (it shipped as a live bug in RC1–RC5).
- No gradients on chrome, no glassmorphism, no colored shadows. The
  one permitted gradient is the black scrim over poster imagery on
  the Detail hero — image treatment, not chrome. Elevation is
  borders before shadows; exactly three shadow tokens
  (`--shadow-card`, `--shadow-pop`, `--shadow-dialog`); dark mode
  elevates by lightening (`--overlay`) + hairline, not shadow.
- Radius scale: xs 4 / sm 6 / md 8 (buttons, inputs, nav rows) /
  lg 10 (cards, rows, posters) / xl 14 (dialogs, sheets). Pills are
  reserved for category chips, the search field, and status badges —
  plus shapes round by nature (progress tracks, status dots, the
  switch, the count badge).
- Type is Anek Bangla only, on the fixed scale in DESIGN.md
  (display 24/30 … micro 10/13), with tabular numerals on every
  updating or aligned digit. Filenames are one line,
  `min-w-0 flex-1 truncate`, full name in `title`.
- Icons are one inline SVG sprite (Lucide-style, 24×24 grid, stroke
  2, `currentColor` only). Status is never color alone — dots pair
  with labels; a disabled server is a hollow dot.

## 3. The no-text-selection rule

A standing user requirement (2026-11-26): the UI must feel like a
native app, not a web page. In `src/styles/global.css`:

- `body` carries `user-select: none` (with the `-webkit-` prefix) —
  dragging the mouse never selects UI text.
- Selection is re-enabled **only** inside real text fields:
  `input`, `textarea`, `[contenteditable="true"]`.
- Images are non-draggable (`-webkit-user-drag: none` on `img`) —
  posters don't ghost-drag.

Any new surface inherits this automatically; overriding it outside a
text field is a defect.

## 4. Motion and theme rules

- Motion tokens: `--dur-fast` 120ms / `--dur-med` 180ms /
  `--dur-slow` 280ms, `--ease-out cubic-bezier(.2,0,0,1)`. Subtle
  only. Never animate layout properties on large lists; progress
  fills may transition width at most (segmented fills patch with no
  transition).
- **Reduced motion:** a global `@media (prefers-reduced-motion:
  reduce)` rule in `global.css` zeroes animation. JavaScript-driven
  motion honors the same preference (e.g. Settings section
  navigation scrolls instantly instead of smooth-scrolling when the
  preference is set).
- **Theme:** `html[data-theme]` carries light/dark; the choice is
  applied pre-paint from the cached preference so there is no flash
  of the wrong theme, then confirmed by the persisted setting.
  Switching suppresses transitions for one frame (the
  `theme-switching` class) so controls don't cross-fade muddy — the
  QA checklist requires the flip to look instant.
- **Accessibility floor** (from DESIGN.md): `:focus-visible` ring
  everywhere (2px `--focus`, offset 2px); semantic buttons/inputs
  only; `aria-label` on icon-only buttons; `aria-pressed` on
  favorite; `role=status`/`aria-live` on status lines; download
  state always icon + text, never color alone; ≥40px
  coarse-pointer targets (44px in the DESIGN.md accessibility
  floor for coarse pointers); contrast per the verified token
  tables.

## 5. How any of this is verified — honestly

**There are no visual regression tests, and no screenshot CI.**
Screenshot/headless CI is banned by the user's standing order
(2026-11-25), and that ban is why this section reads the way it
does. Verification is three weaker-but-real layers:

1. **Static guardrails in CI** (frontend job of
   `.github/workflows/desktop-tauri.yml`): after the Astro build,
   the step greps the compiled CSS in `dist/_astro/*.css` to prove
   the responsive rules *survived compilation* — the media bands
   (640/768/1280/1536/1920px, accepting both classic `min-width`
   and Lightning CSS range syntax), a safe-area inset, no fixed
   width over 240px, and no `w-[≥300px]` utility in `src/`. These
   are tripwires. The workflow's own comment says what they are:
   they prove the rules exist in the output, **not** that anything
   renders correctly.
2. **Vitest pure-logic tests** pin formatting/derivation helpers in
   `src/lib/*.ts` (node environment, no DOM). They claim nothing
   about layout. A known, stated limit: `tsc` does not parse
   `.astro` files, so the page script is verified by build +
   review, not by the type checker
   (`docs/ENGINEERING-STANDARDS.md` §3).
3. **The human pass is the real layout test:**
   `desktop-tauri/RESPONSIVE-QA.md` — 11 viewports (320×720 through
   2560×1440) × 2 themes (dark first, then light), every screen
   (Library, Detail, Transfers, Servers, Settings, plus onboarding
   on a fresh profile), ~20 minutes, run on the release candidate
   before any final tag. Universal criteria per row: no horizontal
   scrollbar, no clipped/overlapping text, navigation reachable,
   visible focus ring, posters load or fall back to the Fetch
   glyph, touch targets feel ≥40px. **A failure in rows 7–11
   (1280×800 and up — the desktop bands) blocks the final
   release.**

If the screenshot ban is ever lifted by the user, layer 3 is what
automated visual tests should encode. Until then, do not describe
the guardrails as layout tests — in docs, in release notes, or in
conversation.
