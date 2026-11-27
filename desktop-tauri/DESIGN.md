# LanFetch Design System (v2 — the redesign contract)

Owner: chief integrator. Every screen, component and agent works
from this document. Backend contracts are untouched by design:
`src/lib/api.ts`, all DTOs, all element IDs and shortcuts are
frozen (see Agent 01/04 reports, goal workspace).

## Identity

> **The chrome is quiet, precise, and neutral — the posters are
> the only color in the room.** If a UI surface competes with
> movie art for attention, the surface is wrong.

- Brand mark: the Fetch glyph — a rounded-square accent tile with
  a download arrow whose head is a play notch (inline SVG symbol
  `#i-logo`). Lockup: glyph + lowercase `lanfetch`, Anek Bangla
  600, ink color. Accent-colored text is forbidden.
- Accent law: indigo `#2b39f0` (light) / `#8e98ff` (dark) is for
  primary actions, active indicators, focus, progress — never
  decoration, never a background wash, never body text.
- Filled accent buttons take `--on-accent` text: white on light
  accent (7.13:1), `#12141f` on dark accent (7.23:1). White on the
  dark accent is 2.60:1 and is FORBIDDEN (this was a live bug in
  RC1–RC5, found by Agent 09).
- No gradients on chrome, no glassmorphism, no colored shadows.
  The one permitted gradient is a black scrim over poster imagery
  on the Detail hero (image treatment, not chrome).
- Elevation: borders before shadows. Three shadow tokens only
  (`--shadow-card`, `--shadow-pop`, `--shadow-dialog`); dark mode
  elevates by lightening (`--overlay`) + hairline, not shadow.
- Radius character: engineered, not bubbly — xs 4 / sm 6 / md 8
  (buttons, inputs, nav rows) / lg 10 (cards, rows, posters) /
  xl 14 (dialogs, sheets). Pills are reserved for exactly three
  things: category chips, the search field, status badges.

## Tokens (all in `src/styles/global.css`, the single source)

The 13 Lanes colors keep their names and (almost) values.
Additions: `--on-accent`, `--accent-hover`, `--border-strong`
(inputs/component boundaries, ~3:1), `--overlay`, `--scrim`,
`--focus` (light = accent; dark = `#b3baff`), `--skeleton`,
`--skeleton-shine`, three shadows per theme. Light `ink-3` is
darkened to `#55605e` (5.42:1 on raised); light canvas de-greened
to `#f3f4f5`. Every existing token was WCAG-verified by Agents
08/09 — the palette skeleton was sound; do not repaint it.

Type scale (Anek Bangla only; replaces all ad-hoc sizes):
display 24/30 · page 20/26 · heading 16/22 · row 14/20 · body
13/19 · control 13/18 · control-sm 12/16 · secondary 12/17 ·
caption 11/15 · overline 11/14 (+0.08em, uppercase — Latin only;
Bangla labels take normal case, no tracking) · micro 10/13.
Tabular numerals on every updating/aligned digit. Filenames:
one line, `min-w-0 flex-1 truncate`, full name in `title`.

Spacing: 4px base; off-scale values (10px, 14px gaps) are banned
in new work. Screen padding 24 desktop / 16 mobile; grid gap
16×20 desktop / 12×16 mobile; card padding 16 / 12 mobile.

Motion: `--dur-fast 120ms / --dur-med 180ms / --dur-slow 280ms`,
`--ease-out cubic-bezier(.2,0,0,1)`. Subtle only; a global
`prefers-reduced-motion` rule zeroes animation. Never animate
layout properties on large lists; progress fills transition
width at most.

## Breakpoints (Agent 03's plan; Tailwind defaults + custom)

- **<768px — phone:** sidebar hidden; fixed bottom nav (4 items +
  badge) with `env(safe-area-inset-bottom)` padding; poster grid
  min 104px (2 cols @320, 3 @430); headers stack; Detail stacks
  (poster 128px); settings labels stack.
- **768–1023px — tablet:** compact icon rail 64px (labels hidden,
  tooltips via `title`), poster min 140px.
- **≥1024px — desktop:** expanded rail 208px, user-collapsible to
  64px (persisted in localStorage); poster min 148→176px by band;
  content caps at 2000px (grid) / 1600px (text screens) centered.
- Touch: `@media (pointer: coarse)` raises chips/buttons/nav to
  ≥40px targets.
- Tauri `minWidth` drops 880 → 720 once the shell survives it
  (compact rail band covers 720–1023); the desktop app never uses
  the phone layout, web mode does.

## Icons (Agent 10's system)

One inline SVG sprite (`<symbol>` set in `index.astro`), Lucide-
style hand-drawn paths, 24×24 grid, stroke 2 round caps,
`currentColor` only. Sizes: 16 inline / 18 buttons / 20 nav /
24 empty states. TS helper `icon(name, size)` in `src/lib/ui.ts`
builds the same nodes for JS-rendered DOM. Unicode ★/☆/← are
removed wherever the sprite lands. Status is never color alone:
dots pair with labels; disabled server = hollow dot.

## Screen laws

- **Library:** sticky header (search + chips stay while the grid
  scrolls); cards are real `<button>`s (focusable, Enter opens);
  hover = ground lift + ring, never poster scaling; skeleton =
  poster block + two text bars; distinct empty states for empty
  catalog / no search results / no favorites / offline, each with
  an action; errors are banners with Retry, never gray footnotes.
- **Detail:** cinematic hero — blurred poster wash (dark:
  blur 48, saturate .65, brightness .5) under a scrim gradient,
  no text below 88% opacity zone; sharp poster floats left;
  meta as chips; file rows show every FileDto field incl.
  `sourceTag`; a paused file can be resumed in place (via
  `start_download` dedup — verify engine behavior in R4); file
  states refresh on downloads-changed while Detail is open.
- **Transfers:** summary strip derived from `tasks[]` (Active /
  Queued / Completed / Failed counts + total speed); rows are
  KEYED by task id and patched in place (the full-rebuild-per-
  event pattern is abolished); segmented progress from
  `TaskDto.segments`; failed = icon + error-colored message;
  Remove and Clear completed ask first (confirm dialog).
- **Servers:** cards with status pill (Online · N ms /
  Unreachable / Disabled / Checking…), switch-style toggle,
  labeled add form with validation state, Remove confirms.
- **Settings:** section cards; ≥1280px two-pane (section nav +
  panel); honest language note stays (no fake translations);
  storage paths get copy buttons; update state uses the
  arrow-up icon when a version exists.

## Accessibility floor

`:focus-visible` ring everywhere (2px `--focus`, offset 2px);
semantic buttons/inputs only; `aria-label` on icon-only buttons;
`aria-pressed` on favorite; `role=status`/`aria-live` on status
lines; download state always icon + text, never color alone;
44px coarse-pointer targets; contrast per Agents 08/09 tables.
