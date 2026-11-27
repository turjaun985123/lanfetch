# LanFetch Frontend Architecture (V2)

Current state: the entire frontend is one file,
`desktop-tauri/src/pages/index.astro` (2,805 lines): markup and the SVG
sprite (lines 1–686), then one inline module script (687–2803) holding
25 top-level mutable variables, every screen's rendering and event
wiring, and the boot sequence. The only seams are the support modules
`src/lib/api.ts` (transport + DTOs), `src/lib/format.ts`, and
`src/lib/ui.ts`. V2 extracts the page script into per-screen modules
(ADR-001) without a framework rewrite. This document is the plan and the
rules; `desktop-tauri/DESIGN.md` remains the visual-system authority.

## Extraction order and mechanics

Extraction order is risk-ordered, not size-ordered. Each step is its
own commit + CI run, and no step starts until the previous is green.

0. **Step 0 — `api.ts` unsubscribe (prerequisite, ~30 min).**
   `onDownloadsChanged(cb)` returns an unsubscribe function; the web
   branch keeps its EventSource in a module-level slot closed by the
   unsubscribe, the Tauri branch resolves the unlisten promise into it.
   Behavior unchanged; shell code gains a teardown path.
1. **Transfers** → `src/lib/transfers.ts` — lowest risk: rendering is
   already centralized, input is one `DownloadListDto`, the refresh
   loop is self-contained behind a stamp guard.
2. **Servers** → `src/lib/servers.ts` — small, isolated; its only
   exports are a refresh function and the form validators.
3. **Settings** → `src/lib/settings.ts` — same shape as Servers.
4. **Library** → `src/lib/library.ts` — the largest module; attempted
   only after the pattern is proven three times.
5. **Detail** → `src/lib/detail.ts` — highest entanglement (shared
   `state.titles` cache, Library's `openTitle` export, poll timer), so
   it goes last, after Library's exports are stable.

Mechanics that keep the big-bang risk out: function bodies move
**verbatim**; the only edits inside moved code are identifier
qualifications (closure variable → `s.` / `api.` / `ctx.` prefixes);
markup, ids, classes, and the boot call order never change; the sprite
stays in the page; the shell script ends under ~700 lines and contains
only shared helpers, `show()`, boot, and the global keydown handler.

## Ownership and state rules

- Every screen's mutable state lives in one per-screen state object
  created by a factory in its module (e.g. `createTransfersState()`).
  No module-level mutable state survives outside factories and the
  shell.
- Cross-module calls go through one explicit `ScreenCtx` interface
  (`api`, `announce`, `show`, `status`, plus narrow call-ins like
  `openTitle` / `refreshTransfers`) — never through imported mutable
  variables. This replaces today's invisible coupling (the page script
  freely reads and writes `state.titles`, `settings`, `favs`).
- The generation/stamp guards (`libGen`, `feedGen`, `detailGen`,
  `dlFetching`/`dlDirty`, detail poll timer, downloads SSE subscription)
  move **with** their screens into module state. Nothing async is
  guarded by a variable owned by another module.

## Disposal rules

- Each module exposes `mount()` / `dispose()`: mount returns the
  cleanup closure; dispose removes listeners and observers. One named
  handler per listener (inline arrow handlers on persistent elements
  are banned — they cannot be removed).
- IntersectionObserver disconnects on dispose; the detail poll timer
  clears on dispose; the downloads subscription unsubscribes on
  dispose.
- Honest limit: in the current single-page app `dispose()` is never
  called — screens are shown/hidden, not unmounted. The contract
  exists so the invariant is testable (a Vitest mount/dispose cycle
  asserting listener counts return to zero) and so a future
  multi-page or framework move inherits a lifecycle instead of today's
  zero `removeEventListener` calls.

## What deliberately stays

- The keyed-row DOM patching in Transfers (the redesign's performance
  core) — extracted as-is, not re-rendered through any framework.
- `api.ts` as the single transport seam, with its DTOs mirroring
  `engine/src/dto.rs`; `format.ts` and `ui.ts` unchanged.
- The inline pre-paint theme/lang scripts in `<head>` (required to
  avoid a flash of wrong theme; documented DESIGN.md deviation from
  the shell's strict CSP posture).

## Testing

Vitest (node environment, no jsdom) covers pure logic only, starting
with the functions that carry real branching: segment-bar geometry,
row signature/action derivation, transition announcements, chip/search
derivation, detail file-action state matrix, settings merge, and
server-form validation. The suite wires into the existing frontend CI
job (`npm ci` → `npm run check` → build → guardrails), so the CI job
is the first place any of it runs (no-local-build rule). DOM behaviour
is not claimed from these tests — layout and interaction truth remain
the human `desktop-tauri/RESPONSIVE-QA.md` checklist (headless CI ban
stands; see TESTING.md §8).

## Risks, named

- **Verbatim-move drift:** a moved function silently picks up the
  wrong binding. Mitigation: mechanical renames only, per-step CI.
- **Boot-order regression:** modules must wire in today's exact order
  (bootstrap → settings apply → screens mount → downloads subscribe).
- **Guard-variable split:** a stamp guard read in two modules would
  desynchronise; the ownership rule above forbids it.
- **The `.astro` script still isn't type-checked** by `tsc` while any
  logic remains inline; extraction shrinks that blind spot step by
  step, and `@astrojs/check` adoption is a separate explicit decision.
