// Shared UI primitives for JS-rendered DOM (redesign Agent 10's
// icon system + helpers). Static markup in index.astro references
// the same sprite symbols directly; everything dynamic goes
// through here so there is exactly one icon source.

const SVGNS = 'http://www.w3.org/2000/svg';

/** Build an `<svg><use href="#i-<name>"/></svg>` node for the
 * inline sprite defined in index.astro. Sizes: 16 inline,
 * 18 buttons/rows, 20 nav, 24 empty states. */
export function icon(name: string, size: 16 | 18 | 20 | 24 = 18): SVGElement {
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
