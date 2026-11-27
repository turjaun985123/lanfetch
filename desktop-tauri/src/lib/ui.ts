// Shared UI primitives for JS-rendered DOM (redesign Agent 10's
// icon system + helpers). Static markup in index.astro references
// the same sprite symbols directly; everything dynamic goes
// through here so there is exactly one icon source.

const SVGNS = 'http://www.w3.org/2000/svg';

/** Build an `<svg><use href="#i-<name>"/></svg>` node for the
 * inline sprite defined in index.astro. Sizes: 16 inline,
 * 18 buttons/rows, 20 nav, 24 empty states. */
export function icon(
  name: string,
  size: 16 | 18 | 20 | 24 | 32 = 18,
): SVGElement {
  const svg = document.createElementNS(SVGNS, 'svg');
  svg.setAttribute('class', 'ic' + (size !== 18 ? ' ic-' + size : ''));
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(SVGNS, 'use');
  use.setAttribute('href', '#i-' + name);
  svg.append(use);
  return svg;
}

/** Small element builder: tag, class, optional text content. */
export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** One option in a custom dropdown. */
export interface DropdownOption {
  value: string;
  label: string;
}

export interface Dropdown {
  root: HTMLElement;
  value(): string;
  setValue(v: string): void;
  setOptions(opts: DropdownOption[]): void;
  destroy(): void;
}

/** A fully custom dropdown. Native `<select>` renders as the OS
 * webview widget — bright system chrome on the dark theme, as the
 * first filter bar proved — so filter controls use this instead:
 * a token-styled button + listbox with full keyboard support
 * (arrows/Home/End move, Enter/Space pick, Esc closes, Tab
 * closes, single-letter type-ahead cycles). The current selection
 * is always kept in the option set by the caller, so setOptions
 * never silently resets it. */
export function createDropdown(opts: {
  id: string;
  label: string;
  options: DropdownOption[];
  value: string;
  onChange: (value: string) => void;
}): Dropdown {
  let options = opts.options;
  let current = opts.value;
  let open = false;
  let active = -1;

  const root = el('div', 'dd');
  root.id = opts.id;
  const btn = el('button', 'dd-btn');
  btn.type = 'button';
  btn.setAttribute('aria-haspopup', 'listbox');
  btn.setAttribute('aria-expanded', 'false');
  btn.setAttribute('aria-label', opts.label);
  const text = el('span', 'dd-text');
  const chev = icon('chevron-down', 16);
  chev.classList.add('dd-chev');
  btn.append(text, chev);
  const list = el('ul', 'dd-list hidden');
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', opts.label);
  root.append(btn, list);

  function renderList(): void {
    list.replaceChildren();
    options.forEach((o, i) => {
      const li = el('li', 'dd-opt');
      li.id = opts.id + '-opt-' + i;
      li.setAttribute('role', 'option');
      li.setAttribute('aria-selected', String(o.value === current));
      if (o.value === current) li.append(icon('check', 16));
      li.append(document.createTextNode(o.label));
      if (i === active) li.classList.add('dd-active');
      li.addEventListener('click', () => pick(o.value));
      li.addEventListener('pointermove', () => setActive(i));
      list.append(li);
    });
  }

  function syncButton(): void {
    const found = options.find((o) => o.value === current);
    text.textContent = found ? found.label : current;
    btn.setAttribute('aria-expanded', String(open));
    if (open && active >= 0)
      btn.setAttribute('aria-activedescendant', opts.id + '-opt-' + active);
    else btn.removeAttribute('aria-activedescendant');
  }

  function setActive(i: number): void {
    active =
      options.length === 0
        ? -1
        : Math.max(0, Math.min(i, options.length - 1));
    [...list.children].forEach((c, j) =>
      c.classList.toggle('dd-active', j === active),
    );
    const node = list.children[active] as HTMLElement | undefined;
    if (node) node.scrollIntoView({ block: 'nearest' });
    syncButton();
  }

  function onDocPointer(e: Event): void {
    if (!root.contains(e.target as Node)) setOpen(false);
  }

  function setOpen(next: boolean): void {
    if (next === open) return;
    open = next;
    list.classList.toggle('hidden', !open);
    if (open) {
      active = Math.max(
        0,
        options.findIndex((o) => o.value === current),
      );
      renderList();
      document.addEventListener('pointerdown', onDocPointer, true);
    } else {
      document.removeEventListener('pointerdown', onDocPointer, true);
    }
    syncButton();
  }

  function pick(v: string): void {
    const changed = v !== current;
    current = v;
    setOpen(false);
    renderList();
    syncButton();
    btn.focus();
    if (changed) opts.onChange(v);
  }

  function onBtnClick(): void {
    setOpen(!open);
  }

  function onKey(e: KeyboardEvent): void {
    if (!open) {
      if (
        e.key === 'ArrowDown' ||
        e.key === 'ArrowUp' ||
        e.key === 'Enter' ||
        e.key === ' '
      ) {
        e.preventDefault();
        setOpen(true);
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setActive(active + 1);
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActive(active - 1);
        break;
      case 'Home':
        e.preventDefault();
        setActive(0);
        break;
      case 'End':
        e.preventDefault();
        setActive(options.length - 1);
        break;
      case 'Enter':
      case ' ':
        e.preventDefault();
        if (active >= 0 && options[active]) pick(options[active].value);
        break;
      case 'Escape':
        e.preventDefault();
        setOpen(false);
        break;
      case 'Tab':
        setOpen(false);
        break;
      default:
        if (e.key.length === 1) {
          const ch = e.key.toLowerCase();
          for (let k = 0; k < options.length; k++) {
            const idx = (active + 1 + k) % options.length;
            if (options[idx].label.toLowerCase().startsWith(ch)) {
              setActive(idx);
              break;
            }
          }
        }
    }
  }

  btn.addEventListener('click', onBtnClick);
  btn.addEventListener('keydown', onKey);
  renderList();
  syncButton();

  return {
    root,
    value: () => current,
    setValue(v: string): void {
      current = v;
      renderList();
      syncButton();
    },
    setOptions(next: DropdownOption[]): void {
      options = next;
      renderList();
      syncButton();
    },
    destroy(): void {
      document.removeEventListener('pointerdown', onDocPointer, true);
      btn.removeEventListener('click', onBtnClick);
      btn.removeEventListener('keydown', onKey);
    },
  };
}

/** Transient feedback (Agent 26/31): status toasts are polite,
 * alert toasts assertive; one event never raises both. Toasts
 * live in the #toast-region rendered by index.astro. */
export function toast(
  message: string,
  kind: 'status' | 'alert' = 'status',
): void {
  const region = document.getElementById('toast-region');
  if (!region) return;
  const node = document.createElement('div');
  node.className = 'toast' + (kind === 'alert' ? ' is-alert' : '');
  node.setAttribute('role', kind === 'alert' ? 'alert' : 'status');
  node.textContent = message;
  region.append(node);
  if (kind === 'alert') {
    // Failures must not vanish on a timer (Agent 37): alert
    // toasts stay until dismissed by click.
    node.title = 'Click to dismiss';
    node.style.pointerEvents = 'auto';
    node.style.cursor = 'pointer';
    node.addEventListener('click', () => node.remove());
  } else {
    window.setTimeout(() => node.remove(), 4000);
  }
}

/** Destructive-action confirmation on the native <dialog>
 * (Agent 26/31): focus lands on Cancel, Esc cancels, focus
 * returns to the invoker natively. Fails safe — without
 * showModal support the action does not happen. */
export function confirmDialog(opts: {
  title: string;
  body: string;
  confirmLabel: string;
}): Promise<boolean> {
  const dlg = document.getElementById(
    'confirm-dialog',
  ) as HTMLDialogElement | null;
  if (!dlg || typeof dlg.showModal !== 'function' || dlg.open)
    return Promise.resolve(false);
  document.getElementById('confirm-title')!.textContent = opts.title;
  document.getElementById('confirm-body')!.textContent = opts.body;
  const ok = document.getElementById('confirm-ok') as HTMLButtonElement;
  const cancel = document.getElementById(
    'confirm-cancel',
  ) as HTMLButtonElement;
  ok.textContent = opts.confirmLabel;
  return new Promise((resolve) => {
    ok.onclick = () => {
      dlg.close();
      resolve(true);
    };
    cancel.onclick = () => {
      dlg.close();
      resolve(false);
    };
    dlg.oncancel = () => resolve(false);
    // Any other dismissal path settles as "not confirmed"
    // instead of leaving the caller hanging (Agent 37).
    dlg.addEventListener('close', () => resolve(false), { once: true });
    dlg.showModal();
    cancel.focus();
  });
}
