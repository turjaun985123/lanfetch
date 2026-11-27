# LanFetch — Responsive QA checklist (human pass)

The CI "Responsive guardrails" step is a **static tripwire**, not a
layout test: it proves the responsive rules exist in the built CSS,
not that any screen actually looks right. This checklist is the real
verification. Run it on the release candidate before any final tag.

Time: ~20 minutes. Tools: the desktop app (resize the window) and
the web build on a phone browser (or a desktop browser's device
toolbar for the small sizes).

## How to run

For each viewport below: open Library, Detail (any title),
Transfers, Servers, Settings — in **dark** theme first, then
**light**. Note pass/fail per row; a failure in rows 7–11 blocks
the final release.

| # | Viewport | Where it matters |
|---|----------|------------------|
| 1 | 320×720 | Smallest phone: bottom nav, search full-width row |
| 2 | 360×800 | Typical Android phone (web build) |
| 3 | 600×800 | Small tablet / large phone landscape |
| 4 | 768×1024 | Tauri minimum width; compact icon rail |
| 5 | 800×600 | Tauri minimum window (768×560 + chrome) |
| 6 | 1024×768 | Compact→expanded rail boundary |
| 7 | 1280×800 | Default window; Settings two-pane starts |
| 8 | 1366×768 | Common laptop |
| 9 | 1536×864 | Wide posters (176px) |
| 10 | 1920×1080 | Ultrawide poster size, content caps |
| 11 | 2560×1440 | Grid capped at 2000px, text screens at 1600px |

## Universal criteria (every row, every screen)

- [ ] No horizontal scrollbar anywhere; no clipped or overlapping text
- [ ] Navigation fully reachable (rail, compact rail, or bottom bar)
- [ ] Focus ring visible on the focused control (Tab through)
- [ ] Posters load, fade in, and failed posters show the Fetch glyph
- [ ] Touch targets feel ≥40px on phone rows (chips, nav, buttons)

## Screen-specific checks

- **Library** — sticky header stays put while the grid scrolls;
  chips scroll sideways with a faded edge; search clear button
  appears when text is present; empty states show for an empty
  category, a no-hit search, and empty Favorites.
- **Detail** — poster wash behind the title, text readable over it
  in both themes; poster + title stack vertically under 640px;
  file rows don't squeeze the action button; a paused file's
  button says **Resume** and is enabled.
- **Transfers** — summary strip shows 4 stats (2×2 on phones);
  progress bars move without the rows jumping; Remove asks for
  confirmation; header buttons move to their own row on phones.
- **Servers** — cards go two-line under 900px without squeezing
  the name; the switch toggles; Add stays disabled until name
  and address are filled; the rail shows per-server dots/names.
- **Settings** — section nav appears at ≥1280px and scrolls to
  sections; theme switch flips instantly (no muddy cross-fade);
  copy buttons on Storage paths show "Copied".
- **Onboarding** (fresh profile only) — card reads cleanly,
  "Start browsing" is the filled button.

## Sign-off

- [ ] All rows pass, or failures are filed as issues with the
      viewport + screen noted
- [ ] No row 7–11 failure is open
