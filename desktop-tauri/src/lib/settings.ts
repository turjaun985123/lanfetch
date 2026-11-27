// Settings screen (V2 Wave 4 step 3, docs/FRONTEND.md) — theme
// included: the theme choice IS a setting, and applyTheme /
// persistSettings were already one flow in the page.
//
// Extracted verbatim from index.astro with only identifier
// qualifications (api. / ctx. prefixes). State (currentSettings)
// lives in the factory closure; boot installs the loaded settings
// via setSettings() and keeps its applyTheme/persistSettings
// aliases. mount() applies the cached theme choice first (the
// page did this inline at the same script position), then wires
// every control with named handlers; dispose removes them all.
import * as api from './api';
import type { SettingsDto } from './api';
import { toast } from './ui';

export interface SettingsCtx {
  /** The shell's element-by-id helper. */
  status: (id: string) => HTMLElement;
}

export interface SettingsScreen {
  /** Install the settings loaded at boot. */
  setSettings: (dto: SettingsDto) => void;
  applyTheme: (choice: string) => void;
  persistSettings: (patch: Partial<SettingsDto>) => Promise<void>;
  /** Apply the cached theme and wire the controls. Returns the
   * dispose closure (FRONTEND.md disposal rules). */
  mount: () => () => void;
}

/** The persist merge: a patch applied over the current settings
 * (FRONTEND.md's settings-merge test target). Pure. */
export function mergeSettings(
  current: SettingsDto,
  patch: Partial<SettingsDto>,
): SettingsDto {
  return { ...current, ...patch };
}

/** Resolve a theme choice to the effective scheme: 'dark' wins
 * outright, 'system' follows the OS preference, anything else
 * is light. Pure — unit-tested. */
export function resolveTheme(
  choice: string,
  systemDark: boolean,
): 'dark' | 'light' {
  return choice === 'dark' || (choice === 'system' && systemDark)
    ? 'dark'
    : 'light';
}

export function createSettings(ctx: SettingsCtx): SettingsScreen {
  // ---- theme -------------------------------------------------------------
  function applyTheme(choice: string) {
    // One-frame transition suppression (Agent 32): without
    // it, every transition-colors control cross-fades muddy
    // when the theme flips.
    const root = document.documentElement;
    root.classList.add('theme-switching');
    // Storage can throw (blocked cookies, strict private
    // modes) — the theme must still apply (Agent 46).
    try {
      localStorage.setItem('lanfetch-theme', choice);
    } catch {
      /* pre-paint cache only; settings.json is the truth */
    }
    const dark =
      resolveTheme(choice, window.matchMedia('(prefers-color-scheme: dark)').matches) ===
      'dark';
    root.dataset.theme = dark ? 'dark' : 'light';
    requestAnimationFrame(() => {
      requestAnimationFrame(() => root.classList.remove('theme-switching'));
    });
    document.querySelectorAll('.theme-btn').forEach((el) => {
      const active =
        (el as HTMLElement).dataset.themeChoice === choice;
      (el as HTMLElement).classList.toggle('is-active', active);
      el.setAttribute('aria-checked', String(active));
      (el as HTMLElement).tabIndex = active ? 0 : -1;
    });
  }

  // ---- settings ---------------------------------------------------------------
  let currentSettings: SettingsDto | null = null;
  async function persistSettings(
    patch: Partial<SettingsDto>,
  ): Promise<void> {
    if (!currentSettings) return;
    try {
      currentSettings = await api.updateSettings(
          mergeSettings(currentSettings, patch),
        );
    } catch {
      // Browser preview or IPC failure: the local UI state the
      // user just set stays as they left it.
    }
  }
  const sel = (id: string) =>
    document.getElementById(id) as HTMLSelectElement;

  function mount(): () => void {
    // The page applied the cached theme inline at this point in
    // the script (before boot loads the real settings).
    let savedTheme = 'system';
    try {
      savedTheme = localStorage.getItem('lanfetch-theme') ?? 'system';
    } catch {
      /* storage unavailable — fall back to system */
    }
    applyTheme(savedTheme);

    const themeBtns = [
      ...document.querySelectorAll('.theme-btn'),
    ] as HTMLElement[];
    const onThemeChoice = (e: Event) => {
      const choice = (e.currentTarget as HTMLElement).dataset
        .themeChoice!;
      applyTheme(choice);
      void persistSettings({ theme: choice });
    };
    // Radiogroup arrows (Agent 31): Left/Right move and select,
    // matching the radio pattern the markup declares.
    const onThemeKeys = (e: KeyboardEvent) => {
      if (
        e.key !== 'ArrowRight' &&
        e.key !== 'ArrowLeft' &&
        e.key !== 'ArrowDown' &&
        e.key !== 'ArrowUp'
      )
        return;
      const btns = [
        ...document.querySelectorAll('.theme-btn'),
      ] as HTMLElement[];
      const i = btns.findIndex(
        (b) => b.getAttribute('aria-checked') === 'true',
      );
      if (i < 0) return;
      const forward = e.key === 'ArrowRight' || e.key === 'ArrowDown';
      const j = forward
        ? (i + 1) % btns.length
        : (i - 1 + btns.length) % btns.length;
      e.preventDefault();
      btns[j]!.focus();
      btns[j]!.click();
    };
    const onConnections = () =>
      void persistSettings({
        connections: parseInt(sel('set-connections').value, 10),
      });
    const onConcurrent = () =>
      void persistSettings({
        maxConcurrent: parseInt(sel('set-concurrent').value, 10),
      });
    const onLanguage = () =>
      void persistSettings({ language: sel('set-language').value });
    // Settings section nav (Agent 24): plain anchors, click marks
    // the section active and smooth-scrolls; no scrollspy, no
    // router.
    const navItems = [
      ...document.querySelectorAll('.settings-nav-item'),
    ] as HTMLElement[];
    const onSettingsNav = (e: Event) => {
      const a = e.currentTarget as HTMLElement;
      e.preventDefault();
      document.querySelectorAll('.settings-nav-item').forEach((x) => {
        x.classList.toggle('is-active', x === a);
        if (x === a) x.setAttribute('aria-current', 'true');
        else x.removeAttribute('aria-current');
      });
      const target = document.getElementById(a.dataset.sec!);
      // JS smooth scrolling ignores the CSS reduced-motion rule —
      // honor the preference here (Agent 44).
      const reduce = window.matchMedia(
        '(prefers-reduced-motion: reduce)',
      ).matches;
      target?.scrollIntoView({
        behavior: reduce ? 'auto' : 'smooth',
        block: 'start',
      });
    };
    // Storage path copy buttons (Agent 24): Clipboard API first,
    // hidden-textarea fallback for insecure web origins (Agent 30
    // — clipboard is undefined on http:// LAN IPs).
    const copyBtns = [
      ...document.querySelectorAll('[data-copy-target]'),
    ] as HTMLElement[];
    const onCopyClick = async (e: Event) => {
      const btn = e.currentTarget as HTMLElement;
      const target = ctx.status(btn.dataset.copyTarget!);
      const text = target.textContent ?? '';
      if (!text || text === '…') return;
      let ok = false;
      try {
        await navigator.clipboard.writeText(text);
        ok = true;
      } catch {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.append(ta);
        ta.select();
        try {
          ok = document.execCommand('copy');
        } catch {
          ok = false;
        }
        ta.remove();
      }
      if (ok) {
        const use = btn.querySelector('use');
        use?.setAttribute('href', '#i-check');
        window.setTimeout(() => use?.setAttribute('href', '#i-copy'), 1500);
        toast('Copied to clipboard');
      } else {
        toast('Copy failed — the path is shown in Settings.', 'alert');
      }
    };

    for (const b of themeBtns) b.addEventListener('click', onThemeChoice);
    const themeSwitch = ctx.status('theme-switch');
    themeSwitch.addEventListener('keydown', onThemeKeys);
    sel('set-connections').addEventListener('change', onConnections);
    sel('set-concurrent').addEventListener('change', onConcurrent);
    sel('set-language').addEventListener('change', onLanguage);
    for (const a of navItems) a.addEventListener('click', onSettingsNav);
    for (const b of copyBtns) b.addEventListener('click', onCopyClick);
    return () => {
      for (const b of themeBtns)
        b.removeEventListener('click', onThemeChoice);
      themeSwitch.removeEventListener('keydown', onThemeKeys);
      sel('set-connections').removeEventListener('change', onConnections);
      sel('set-concurrent').removeEventListener('change', onConcurrent);
      sel('set-language').removeEventListener('change', onLanguage);
      for (const a of navItems)
        a.removeEventListener('click', onSettingsNav);
      for (const b of copyBtns)
        b.removeEventListener('click', onCopyClick);
    };
  }

  function setSettings(dto: SettingsDto): void {
    currentSettings = dto;
  }

  return { setSettings, applyTheme, persistSettings, mount };
}
