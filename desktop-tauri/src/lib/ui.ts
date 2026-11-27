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
  window.setTimeout(() => node.remove(), 4000);
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
  if (!dlg || typeof dlg.showModal !== 'function')
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
    dlg.showModal();
    cancel.focus();
  });
}
