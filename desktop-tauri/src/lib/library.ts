// Library screen (V2 Wave 4 step 4, docs/FRONTEND.md) — feed,
// search, chips, banners and empty states: the largest screen,
// and the one whose generation discipline (libGen) the whole
// catalog UX stands on.
//
// Extracted verbatim from index.astro with only identifier
// qualifications (api. / ctx. prefixes). All feed/search state
// lives in the factory closure; cross-module traffic goes
// through LibraryCtx (card clicks open Detail via ctx.openDetail;
// the nocats empty state navigates via ctx.show) plus the two
// exports other code legitimately needs: loadLibrary + showBanner
// for boot, and probeStatus for the Servers screen's
// probe -> lib-status coupling (which read this state directly
// while it lived in the page). Wiring lives in mount() as named
// handlers; dispose removes them and disconnects the sentinel
// observer.
import * as api from './api';
import type { CategoryDto, FeedPageDto, TitleDto } from './api';
import {
  createDropdown,
  createMultiDropdown,
  el,
  icon,
  type Dropdown,
  type MultiDropdown,
} from './ui';

export interface LibraryCtx {
  /** The shell's element-by-id helper. */
  status: (id: string) => HTMLElement;
  /** Switch the visible view (shell-owned). */
  show: (view: string) => void;
  /** Open a title's detail (Detail screen, shell-wired). */
  openDetail: (t: TitleDto) => void;
}

export interface LibraryScreen {
  loadLibrary: (refresh: boolean) => Promise<void>;
  showBanner: (
    kind: 'error' | 'info',
    msg: string,
    retryLabel: string | null,
    retry: (() => void) | null,
  ) => void;
  /** The Servers probe coupling: while the library is untouched
   * (nothing painted, no active search), mirror probe health
   * into the library status line. */
  probeStatus: (up: number) => void;
  /** Wire banner/empty/grid/search/sentinel handlers. Returns
   * the dispose closure (FRONTEND.md disposal rules). */
  mount: () => () => void;
}

/** Chip state derivation (Agent 18): a chip is active only when
 * no search is running; the favorites chip follows
 * favoritesActive; a category chip follows currentCategory while
 * favorites are inactive. Pure — unit-tested. */
export function chipActive(
  kind: string | undefined,
  value: string,
  s: {
    activeQuery: string;
    favoritesActive: boolean;
    currentCategory: string | null;
  },
): boolean {
  return s.activeQuery !== ''
    ? false
    : kind === 'fav'
      ? s.favoritesActive
      : kind === 'cat' &&
        !s.favoritesActive &&
        value === (s.currentCategory ?? '');
}

/** The card's corner badge: season count for series, best
 * quality otherwise. Pure — unit-tested. */
export function cardBadge(t: TitleDto): string | null {
  return t.kind === 'series'
    ? t.seasonCount > 1
      ? t.seasonCount + ' Seasons'
      : 'Series'
    : t.bestQuality;
}

/** The card's accessible name: everything the badges show
 * visually, in text (Agent 17). Pure — unit-tested. */
export function cardAriaLabel(t: TitleDto): string {
  return (
    t.title +
    (t.year ? ', ' + t.year : '') +
    (t.kind === 'series'
      ? ', series' +
        (t.seasonCount > 1 ? ', ' + t.seasonCount + ' seasons' : '') +
        (t.bestQuality ? ', ' + t.bestQuality : '')
      : t.bestQuality
        ? ', ' + t.bestQuality
        : '') +
    (t.isFavorite ? ', favorited' : '')
  );
}

/** Library filters — ANDROID PARITY (2026-11-28): the model,
 * semantics and sorts port the APK's CatalogFilter /
 * TitleFilterEngine exactly (the user judged the desktop filter
 * against the APK and found it wanting): multi-select facet SETS
 * (empty set = no constraint; OR within a dimension, AND across
 * dimensions), an inclusive year range that excludes year-less
 * titles whenever a bound is set, a minimum-size threshold, a
 * dual-audio toggle, and the APK's five sorts — Newest (the feed
 * order itself), Title, Year, Size down, Size up. Applied
 * client-side to the titles loaded so far; feed pages, favorites
 * and search results all flow through the same loaded set. The
 * controls are custom dropdowns (ui.ts), never native selects.
 * Pure core — unit-tested. */
export type TitleSort = 'feed' | 'title' | 'year-desc' | 'size-desc' | 'size-asc';

export interface TitleFilters {
  kinds: string[];
  qualities: string[];
  languages: string[];
  sources: string[];
  sourceTags: string[];
  yearFrom: number | null;
  yearTo: number | null;
  minSizeBytes: number | null;
  dualAudioOnly: boolean;
  sort: TitleSort;
}

export const DEFAULT_FILTERS: TitleFilters = {
  kinds: [],
  qualities: [],
  languages: [],
  sources: [],
  sourceTags: [],
  yearFrom: null,
  yearTo: null,
  minSizeBytes: null,
  dualAudioOnly: false,
  sort: 'feed',
};

/** The catalog's quality ladder, best → worst (facet option
 * ordering, mirroring TitleFilterEngine.qualityOrder). */
export const QUALITY_ORDER = [
  '2160p',
  '1080p',
  '720p',
  '480p',
  '360p',
  'CAM',
];

/** Minimum-size presets from the APK's filter sheet. */
export const SIZE_PRESETS: { value: string; label: string }[] = [
  { value: '', label: 'Any size' },
  { value: '700000000', label: '700 MB +' },
  { value: '1500000000', label: '1.5 GB +' },
  { value: '4000000000', label: '4 GB +' },
];

export function filtersActive(f: TitleFilters): boolean {
  return (
    f.kinds.length > 0 ||
    f.qualities.length > 0 ||
    f.languages.length > 0 ||
    f.sources.length > 0 ||
    f.sourceTags.length > 0 ||
    f.yearFrom !== null ||
    f.yearTo !== null ||
    f.minSizeBytes !== null ||
    f.dualAudioOnly ||
    f.sort !== 'feed'
  );
}

export function titleMatchesFilters(t: TitleDto, f: TitleFilters): boolean {
  if (f.kinds.length > 0 && !f.kinds.includes(t.kind)) return false;
  if (f.qualities.length > 0 && !(t.bestQuality !== null && f.qualities.includes(t.bestQuality)))
    return false;
  if (f.languages.length > 0 && !(t.language !== null && f.languages.includes(t.language)))
    return false;
  if (f.sources.length > 0 && !(t.sourceName !== null && f.sources.includes(t.sourceName)))
    return false;
  if (f.sourceTags.length > 0 && !(t.sourceTag !== null && f.sourceTags.includes(t.sourceTag)))
    return false;
  // A set year bound excludes year-less titles (the APK's rule).
  if (f.yearFrom !== null && !(t.year !== null && t.year >= f.yearFrom)) return false;
  if (f.yearTo !== null && !(t.year !== null && t.year <= f.yearTo)) return false;
  // Unknown size is -1, so it fails every positive threshold.
  if (f.minSizeBytes !== null && !(t.maxSizeBytes >= f.minSizeBytes)) return false;
  if (f.dualAudioOnly && !t.hasDualAudio) return false;
  return true;
}

const byTitle = (a: TitleDto, b: TitleDto): number =>
  a.title.toLowerCase().localeCompare(b.title.toLowerCase());

/** Filter + sort a loaded title list without mutating it. */
export function applyTitleFilters(
  titles: TitleDto[],
  f: TitleFilters,
): TitleDto[] {
  const out = titles.filter((t) => titleMatchesFilters(t, f));
  switch (f.sort) {
    case 'title':
      out.sort(byTitle);
      break;
    case 'year-desc':
      out.sort(
        (a, b) =>
          (b.year ?? Number.MIN_SAFE_INTEGER) -
            (a.year ?? Number.MIN_SAFE_INTEGER) || byTitle(a, b),
      );
      break;
    case 'size-desc':
      out.sort((a, b) => b.maxSizeBytes - a.maxSizeBytes || byTitle(a, b));
      break;
    case 'size-asc': {
      // The APK sends unknown sizes (-1) LAST ascending.
      const key = (t: TitleDto): number =>
        t.maxSizeBytes < 0 ? Number.MAX_SAFE_INTEGER : t.maxSizeBytes;
      out.sort((a, b) => key(a) - key(b) || byTitle(a, b));
      break;
    }
    default:
      break; // 'feed' — the crawl order IS Android's Newest.
  }
  return out;
}

export function createLibrary(ctx: LibraryCtx): LibraryScreen {
  // ---- library feed -------------------------------------------------------
  // The UX laws this implements (the Rune build broke all three):
  // a click takes effect immediately, a newer load supersedes an
  // older one via generation numbers on BOTH sides, and stale
  // results never paint.
  let libGen = 0;
  let feedGen = 0;
  let feedHasMore = false;
  let pageLoading = false;
  let currentCategory: string | null = null;
  let titleCount = 0;

  // ---- title filters ------------------------------------------------------
  // Every title painted this generation is retained in
  // loadedTitles so a filter change can repaint from memory
  // without another server round-trip. titleCount counts the
  // loaded set; paintedCount counts what survived the filters.
  let filters: TitleFilters = { ...DEFAULT_FILTERS, kinds: [], qualities: [], languages: [], sources: [], sourceTags: [] };
  type SingleField = 'yearFrom' | 'yearTo' | 'size' | 'sort';
  type MultiField = 'kinds' | 'qualities' | 'languages' | 'sources' | 'sourceTags';
  /** Custom dropdown instances, created in mount() (loaders only
   * run after mount, so the nulls are never observed). */
  const filterDds: Record<SingleField, Dropdown | null> = {
    yearFrom: null,
    yearTo: null,
    size: null,
    sort: null,
  };
  const filterMds: Record<MultiField, MultiDropdown | null> = {
    kinds: null,
    qualities: null,
    languages: null,
    sources: null,
    sourceTags: null,
  };
  let loadedTitles: TitleDto[] = [];
  let paintedCount = 0;
  let statusBase = '';
  function renderStatus(): void {
    ctx.status('lib-status').textContent = filtersActive(filters)
      ? paintedCount + ' of ' + titleCount + ' titles shown · filters on'
      : statusBase;
  }

  // ---- library banner + empty states (Agents 16/25) ---------------------
  // Errors are banners with Retry, never gray footnotes in the
  // status line; empty results are calm empty states. The two
  // never substitute for each other.
  let bannerRetry: (() => void) | null = null;
  function showBanner(
    kind: 'error' | 'info',
    msg: string,
    retryLabel: string | null,
    retry: (() => void) | null,
  ): void {
    const banner = ctx.status('lib-banner');
    banner.classList.remove('hidden');
    const iconSpan = ctx.status('lib-banner-icon');
    iconSpan.className = kind === 'error' ? 'text-failed' : 'text-ink-2';
    iconSpan.replaceChildren(icon(kind === 'error' ? 'warning' : 'info', 20));
    ctx.status('lib-banner-text').textContent = msg;
    const btn = ctx.status('lib-banner-retry') as HTMLButtonElement;
    bannerRetry = retry;
    if (retry && retryLabel) {
      btn.classList.remove('hidden');
      btn.replaceChildren(icon('retry', 16), document.createTextNode(retryLabel));
    } else {
      btn.classList.add('hidden');
    }
  }
  function hideBanner(): void {
    ctx.status('lib-banner').classList.add('hidden');
    bannerRetry = null;
  }

  ctx.status('lib-banner-close').addEventListener('click', () => hideBanner());

  let emptyAction: (() => void) | null = null;
  function setEmpty(
    variant: 'feed' | 'search' | 'favorites' | 'nocats' | 'filters',
  ): void {
    const wrap = ctx.status('lib-empty');
    wrap.classList.remove('hidden');
    const iconSpan = ctx.status('lib-empty-icon');
    const title = ctx.status('lib-empty-title');
    const body = ctx.status('lib-empty-body');
    const action = ctx.status('lib-empty-action') as HTMLButtonElement;
    if (variant === 'nocats') {
      iconSpan.replaceChildren(icon('servers', 24));
      title.textContent = 'No categories found';
      body.textContent =
        'Your servers returned no categories. Check that at least one server is enabled and reachable.';
      action.textContent = 'Open Servers';
      emptyAction = () => ctx.show('servers');
    } else if (variant === 'search') {
      iconSpan.replaceChildren(icon('search', 24));
      title.textContent = 'No results for “' + activeQuery + '”';
      body.textContent =
        'Nothing on the servers matches that title. Check the spelling, or try a shorter search.';
      action.textContent = 'Clear search';
      emptyAction = () => clearSearch();
    } else if (variant === 'filters') {
      iconSpan.replaceChildren(icon('film', 24));
      title.textContent = 'No titles match these filters';
      body.textContent =
        'The filters apply to the titles loaded so far. Scroll to load more, widen the filters, or clear them.';
      action.textContent = 'Clear filters';
      emptyAction = () => clearFilters();
    } else if (variant === 'favorites') {
      iconSpan.replaceChildren(icon('star', 24));
      title.textContent = 'No favorites yet';
      body.textContent =
        'Open a title and tap the star to keep it here. Favorites are saved on this device.';
      action.textContent = 'Browse library';
      emptyAction = () => {
        currentCategory = null;
        favoritesActive = false;
        void openFeed();
      };
    } else {
      iconSpan.replaceChildren(icon('film', 24));
      title.textContent = 'Nothing here yet';
      body.textContent =
        'No titles in this category right now. If the servers just came online, Refresh reloads the catalog.';
      action.textContent = 'Refresh';
      emptyAction = () => void loadLibrary(true);
    }
  }
  function hideEmpty(): void {
    ctx.status('lib-empty').classList.add('hidden');
  }


  function showSkeletons(): void {
    const grid = ctx.status('grid');
    grid.replaceChildren();
    grid.setAttribute('aria-busy', 'true');
    for (let i = 0; i < 12; i++) {
      const card = el('div', 'skeleton-card');
      card.setAttribute('aria-hidden', 'true');
      const frame = el('div', 'skeleton skeleton-poster');
      const bar1 = el('div', 'skeleton mt-2 h-3 w-[85%]');
      const bar2 = el('div', 'skeleton mt-1 h-2.5 w-[55%]');
      card.append(frame, bar1, bar2);
      grid.append(card);
    }
  }

  // Poster failures are delegated (Agent 33): one capturing
  // listener instead of one per card. A failed poster reveals
  // the glyph tile underneath — and because failures are not
  // remembered server-side, the next visit can still succeed.


  function makeCard(
    t: TitleDto,
    posterSrc: string | null,
    eager = false,
  ): HTMLElement {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'poster-card';
    // Identity for syncGrid's DOM reconciliation (one card per
    // folder in every server-produced list).
    card.dataset.key = t.folderUrl;
    // The accessible name carries everything the badges show
    // visually (Agent 17): badges themselves are decorative.
    card.setAttribute('aria-label', cardAriaLabel(t));
    const frame = el('span', 'poster-frame');
    const fallback = el('span', 'poster-fallback');
    fallback.setAttribute('aria-hidden', 'true');
    const glyph = icon('logo', 32);
    glyph.style.opacity = '0.35';
    fallback.append(glyph);
    frame.append(fallback);
    if (posterSrc) {
      const img = document.createElement('img');
      img.className = 'poster-img';
      img.loading = eager ? 'eager' : 'lazy';
      if (eager) img.setAttribute('fetchpriority', 'high');
      img.decoding = 'async';
      img.draggable = false;
      img.alt = '';
      img.addEventListener('load', () => img.classList.add('is-loaded'));
      img.src = posterSrc;
      if (img.complete && img.naturalWidth > 0)
        img.classList.add('is-loaded');
      frame.append(img);
    }
    const badgeText = cardBadge(t);
    if (badgeText) {
      const badge = el(
        'span',
        'badge badge-scrim absolute bottom-1.5 left-1.5',
        badgeText,
      );
      badge.setAttribute('aria-hidden', 'true');
      frame.append(badge);
    }
    if (t.isFavorite) {
      const fav = el('span', 'badge badge-scrim absolute right-1.5 top-1.5');
      fav.setAttribute('aria-hidden', 'true');
      fav.append(icon('star-filled', 16));
      frame.append(fav);
    }
    const name = el('span', 'poster-title', t.title);
    const meta = el(
      'span',
      'poster-meta',
      (t.year ? t.year + ' · ' : '') +
        (t.language ? t.language + ' · ' : '') +
        t.sourceName +
        (t.copies > 1 ? ' · ' + t.copies + ' copies' : ''),
    );
    card.append(frame, name, meta);
    card.addEventListener('click', () => void ctx.openDetail(t));
    return card;
  }

  /** Sync the grid's children to exactly `view`, in order.
   * Existing cards are MOVED, never recreated: the previous
   * replaceChildren repaint emptied the grid before rebuilding
   * it, the page height collapsed mid-scroll, and the browser
   * clamped the scroll position back to the top — on every
   * sorted page load and every filter change, new pages appeared
   * to never arrive (user bug report, 2026-11-28). Only titles
   * without a card yet get one built, after one batched poster
   * registration. Returns false when the generation was
   * superseded mid-registration — the caller drops everything. */
  async function syncGrid(
    view: TitleDto[],
    gen: number,
    first: boolean,
  ): Promise<boolean> {
    const grid = ctx.status('grid');
    if (first) grid.replaceChildren();
    const existing = new Map<string, HTMLElement>();
    for (const child of [...grid.children]) {
      const key = (child as HTMLElement).dataset.key;
      if (key) existing.set(key, child as HTMLElement);
    }
    const missing = view.filter((t) => !existing.has(t.folderUrl));
    const withPosters = missing.filter((t) => t.posterUrl);
    const srcByUrl = new Map<string, string>();
    if (withPosters.length > 0) {
      const srcs = await api.posterSrcs(
        withPosters.map((t) => t.posterUrl!),
      );
      if (gen !== libGen) return false; // superseded while registering
      withPosters.forEach((t, i) => srcByUrl.set(t.posterUrl!, srcs[i]!));
    }
    const keep = new Set<string>();
    view.forEach((t, i) => {
      keep.add(t.folderUrl);
      let card = existing.get(t.folderUrl);
      if (!card) {
        card = makeCard(
          t,
          t.posterUrl ? (srcByUrl.get(t.posterUrl) ?? null) : null,
          first && i < 12,
        );
        existing.set(t.folderUrl, card);
      }
      // appendChild moves a node that is already in the grid, so
      // walking the view in order leaves the grid in exactly view
      // order with every surviving card's DOM state (poster load
      // state included) intact — and the scroll position unmoved.
      grid.append(card);
    });
    for (const [key, card] of existing) {
      if (!keep.has(key)) card.remove();
    }
    if (first) grid.removeAttribute('aria-busy');
    return true;
  }

  /** Repaint the whole loaded set through the current filters,
   * preserving scroll and surviving cards (syncGrid). Returns
   * false when superseded mid-paint. */
  async function repaintLoaded(gen: number): Promise<boolean> {
    const view = applyTitleFilters(loadedTitles, filters);
    paintedCount = view.length;
    return syncGrid(view, gen, false);
  }

  let dynOptSig = '';
  let dualOnly = false;
  function syncFilterChrome(): void {
    ctx
      .status('lib-filter-clear')
      .classList.toggle('hidden', !filtersActive(filters));
    // Facet options follow what has actually loaded (the APK
    // computes facets from its pool the same way); selections are
    // preserved across rebuilds so a control never silently
    // resets while paging. A signature cache keeps paging from
    // re-rendering the lists on every page.
    const distinct = (pick: (t: TitleDto) => string | null): string[] => [
      ...new Set(loadedTitles.map(pick).filter((v): v is string => !!v)),
    ];
    const langs = distinct((t) => t.language).sort();
    const sources = distinct((t) => t.sourceName).sort();
    const tags = distinct((t) => t.sourceTag).sort();
    const quals = distinct((t) => t.bestQuality).sort(
      (a, b) => QUALITY_ORDER.indexOf(a) - QUALITY_ORDER.indexOf(b),
    );
    const years = [
      ...new Set(
        loadedTitles
          .map((t) => t.year)
          .filter((y): y is number => y !== null),
      ),
    ]
      .sort((a, b) => a - b)
      .map(String);
    const sig = [
      langs.join(','),
      sources.join(','),
      tags.join(','),
      quals.join(','),
      years.join(','),
      String(filters.yearFrom),
      String(filters.yearTo),
    ].join('|');
    if (sig === dynOptSig) return;
    dynOptSig = sig;
    const toOpts = (values: string[]) =>
      values.map((v) => ({ value: v, label: v }));
    filterMds.languages?.setOptions(toOpts(langs));
    filterMds.sources?.setOptions(toOpts(sources));
    filterMds.sourceTags?.setOptions(toOpts(tags));
    filterMds.qualities?.setOptions(toOpts(quals));
    const yearOpts = (
      base: string,
      current: number | null,
    ): { value: string; label: string }[] => {
      const opts = [{ value: '', label: base }];
      for (const y of years)
        if (!opts.some((o) => o.value === y)) opts.push({ value: y, label: y });
      if (current !== null && !opts.some((o) => o.value === String(current)))
        opts.push({ value: String(current), label: String(current) });
      return opts;
    };
    filterDds.yearFrom?.setOptions(yearOpts('Year from', filters.yearFrom));
    filterDds.yearTo?.setOptions(yearOpts('Year to', filters.yearTo));
  }

  async function applyFiltersAndRepaint(): Promise<void> {
    const gen = libGen;
    if (!(await repaintLoaded(gen))) return;
    renderStatus();
    syncFilterChrome();
    if (loadedTitles.length > 0 && paintedCount === 0) setEmpty('filters');
    else if (loadedTitles.length > 0) hideEmpty();
  }

  function readFilterControls(): void {
    const yearBound = (v: string | undefined): number | null => {
      if (v === undefined || v === '') return null;
      const n = Number(v);
      return Number.isFinite(n) ? n : null;
    };
    const sizeVal = filterDds.size?.value() ?? '';
    filters = {
      kinds: filterMds.kinds?.values() ?? [],
      qualities: filterMds.qualities?.values() ?? [],
      languages: filterMds.languages?.values() ?? [],
      sources: filterMds.sources?.values() ?? [],
      sourceTags: filterMds.sourceTags?.values() ?? [],
      yearFrom: yearBound(filterDds.yearFrom?.value()),
      yearTo: yearBound(filterDds.yearTo?.value()),
      minSizeBytes: sizeVal === '' ? null : Number(sizeVal),
      dualAudioOnly: dualOnly,
      sort: (filterDds.sort?.value() ?? 'feed') as TitleSort,
    };
  }

  function clearFilters(): void {
    filters = {
      ...DEFAULT_FILTERS,
      kinds: [],
      qualities: [],
      languages: [],
      sources: [],
      sourceTags: [],
    };
    dualOnly = false;
    const dualBtn = ctx.status('lib-filter-dual');
    dualBtn.setAttribute('aria-pressed', 'false');
    dualBtn.classList.remove('is-active');
    filterMds.kinds?.setValues([]);
    filterMds.qualities?.setValues([]);
    filterMds.languages?.setValues([]);
    filterMds.sources?.setValues([]);
    filterMds.sourceTags?.setValues([]);
    filterDds.yearFrom?.setValue('');
    filterDds.yearTo?.setValue('');
    filterDds.size?.setValue('');
    filterDds.sort?.setValue('feed');
    void applyFiltersAndRepaint();
  }

  async function appendPage(
    page: FeedPageDto,
    gen: number,
    first: boolean,
  ): Promise<void> {
    feedHasMore = page.hasMore;
    loadedTitles.push(...page.titles);
    titleCount += page.titles.length;
    // One path for every sort order: syncGrid inserts the new
    // page's cards at their sorted positions and moves (never
    // recreates) the ones already on screen, so paging can no
    // longer reset the scroll position.
    const view = applyTitleFilters(loadedTitles, filters);
    paintedCount = view.length;
    if (!(await syncGrid(view, gen, first))) return;
    statusBase =
      titleCount + ' titles' + (feedHasMore ? ' · scroll for more' : '');
    renderStatus();
    syncFilterChrome();
    if (first) {
      if (titleCount === 0) setEmpty('feed');
      else if (paintedCount === 0) setEmpty('filters');
      else hideEmpty();
      hideBanner();
    }
  }

  async function openFeed(): Promise<void> {
    const gen = ++libGen;
    activeQuery = '';
    favoritesActive = false;
    titleCount = 0;
    loadedTitles = [];
    paintedCount = 0;
    pageLoading = true;
    paintChips();
    showSkeletons();
    hideEmpty();
    syncSearchChrome();
    ctx.status('lib-status').textContent = 'Loading…';
    try {
      const page = await api.feedOpen(currentCategory);
      if (gen !== libGen || page.superseded) return;
      feedGen = page.generation;
      await appendPage(page, gen, true);
    } catch {
      if (gen === libGen) {
        ctx.status('grid').replaceChildren();
        ctx.status('grid').removeAttribute('aria-busy');
        showBanner(
          'error',
          'Could not load the catalog — are the servers reachable?',
          'Retry',
          () => void openFeed(),
        );
      }
    } finally {
      if (gen === libGen) pageLoading = false;
    }
  }

  let pageErrorGen = -1;
  async function loadMore(): Promise<void> {
    if (pageLoading || !feedHasMore) return;
    pageLoading = true;
    const gen = libGen;
    try {
      const page = await api.feedNext(feedGen);
      if (gen !== libGen || page.superseded) return;
      await appendPage(page, gen, false);
    } catch {
      // Not fatal — the sentinel retries on the next scroll
      // pass — but a persistent failure must surface
      // somewhere (Agent 41): banner, once per generation.
      if (pageErrorGen !== gen) {
        pageErrorGen = gen;
        showBanner('error', 'Could not load more titles.', 'Retry', () =>
          void loadMore(),
        );
      }
    } finally {
      // Guarded like every other loader (audit slice 6): a stale
      // loadMore settling after a newer operation started must
      // not clear that operation's flag — the sentinel would
      // fire mid-load and pages would arrive out of order.
      if (gen === libGen) pageLoading = false;
    }
  }

  let favoritesActive = false;
  async function openFavorites(): Promise<void> {
    const gen = ++libGen;
    favoritesActive = true;
    activeQuery = '';
    feedHasMore = false;
    titleCount = 0;
    loadedTitles = [];
    paintedCount = 0;
    pageLoading = true;
    paintChips();
    showSkeletons();
    hideEmpty();
    syncSearchChrome();
    ctx.status('lib-status').textContent = 'Loading favorites…';
    try {
      const favs = await api.getFavorites();
      if (gen !== libGen) return;
      loadedTitles = favs;
      if (!(await repaintLoaded(gen))) return;
      titleCount = favs.length;
      statusBase = favs.length + ' favorites';
      renderStatus();
      syncFilterChrome();
      if (favs.length === 0) setEmpty('favorites');
      else if (paintedCount === 0) setEmpty('filters');
      else hideEmpty();
      hideBanner();
    } catch {
      if (gen === libGen) {
        ctx.status('grid').removeAttribute('aria-busy');
        showBanner('error', 'Could not load favorites.', 'Retry', () =>
          void openFavorites(),
        );
      }
    } finally {
      if (gen === libGen) pageLoading = false;
    }
  }

  // Chip state is derived, never toggled ad hoc (Agent 18):
  // paintChips() re-derives is-active / aria-pressed / roving
  // tabindex from (activeQuery, favoritesActive, currentCategory)
  // — including after a search, which selects no chip. That is
  // the fix for the old bug where searching from Favorites left
  // the Favorites chip highlighted.
  function paintChips(scroll = false): void {
    const wrap = ctx.status('chips');
    let roving: HTMLElement | null = null;
    let fallback: HTMLElement | null = null;
    wrap.querySelectorAll('.chip').forEach((el) => {
      const b = el as HTMLElement;
      const kind = b.dataset.chipKind;
      const active = chipActive(kind, b.dataset.chipValue ?? '', {
        activeQuery,
        favoritesActive,
        currentCategory,
      });
      b.classList.toggle('is-active', active);
      b.setAttribute('aria-pressed', String(active));
      if (active) roving = b;
      if (
        kind === 'cat' &&
        (b.dataset.chipValue ?? '') === (currentCategory ?? '')
      )
        fallback = b;
    });
    const target = roving ?? fallback ?? wrap.querySelector('.chip');
    wrap.querySelectorAll('.chip').forEach((el) => {
      (el as HTMLElement).tabIndex = el === target ? 0 : -1;
    });
    const favIcon = wrap.querySelector('.fav-chip-icon');
    if (favIcon)
      favIcon.replaceChildren(
        icon(favoritesActive ? 'star-filled' : 'star', 16),
      );
    if (scroll && target)
      (target as HTMLElement).scrollIntoView({
        block: 'nearest',
        inline: 'nearest',
      });
  }

  function renderChips(categories: CategoryDto[]): void {
    const wrap = ctx.status('chips');
    wrap.replaceChildren();
    const mk = (
      label: string,
      value: string | null,
      sources?: string[],
    ) => {
      const b = document.createElement('button');
      b.className = 'chip';
      b.dataset.chipKind = 'cat';
      b.dataset.chipValue = value ?? '';
      b.textContent = label;
      if (sources && sources.length > 0)
        b.title = 'On: ' + sources.join(', ');
      b.addEventListener('click', () => {
        if (currentCategory === value && !favoritesActive) return;
        currentCategory = value;
        favoritesActive = false;
        void openFeed();
      });
      return b;
    };
    wrap.append(mk('All', null));
    const favChip = document.createElement('button');
    favChip.className = 'chip';
    favChip.dataset.chipKind = 'fav';
    const favIcon = el('span', 'fav-chip-icon');
    favIcon.setAttribute('aria-hidden', 'true');
    favChip.append(favIcon, document.createTextNode('Favorites'));
    favChip.addEventListener('click', () => {
      if (favoritesActive) return;
      void openFavorites();
    });
    wrap.append(favChip);
    for (const c of categories) wrap.append(mk(c.name, c.name, c.sources));
    // Roving tabindex (Agent 18): arrows move focus only,
    // Enter/Space activate via the native click.
    wrap.onkeydown = (e) => {
      const chips = [...wrap.querySelectorAll('.chip')] as HTMLElement[];
      const i = chips.indexOf(document.activeElement as HTMLElement);
      if (i < 0) return;
      let j: number | null = null;
      if (e.key === 'ArrowRight') j = (i + 1) % chips.length;
      else if (e.key === 'ArrowLeft')
        j = (i - 1 + chips.length) % chips.length;
      else if (e.key === 'Home') j = 0;
      else if (e.key === 'End') j = chips.length - 1;
      if (j !== null) {
        e.preventDefault();
        chips.forEach((c, k) => (c.tabIndex = k === j ? 0 : -1));
        chips[j]!.focus();
      }
    };
    paintChips();
  }

  async function loadLibrary(refresh: boolean): Promise<void> {
    try {
      const categories = await api.getCategories(refresh);
      if (categories.length === 0) {
        // An empty catalog is an empty state, not a status
        // line footnote (Agents 25/41).
        ctx.status('lib-status').textContent = '';
        ctx.status('grid').replaceChildren();
        setEmpty('nocats');
        return;
      }
      hideEmpty();
      hideBanner();
      renderChips(categories);
      void openFeed();
    } catch {
      showBanner(
        'error',
        'Could not load categories — are the servers reachable?',
        'Retry',
        () => void loadLibrary(true),
      );
    }
  }





  // ---- search ---------------------------------------------------------------
  // Search and the feed share one generation counter: whichever
  // starts last owns the grid, and the loser's results are
  // dropped even if they arrive later.
  let searchTimer: number | undefined;
  let activeQuery = '';
  async function runSearch(q: string): Promise<void> {
    const gen = ++libGen;
    activeQuery = q;
    favoritesActive = false;
    feedHasMore = false;
    titleCount = 0;
    loadedTitles = [];
    paintedCount = 0;
    pageLoading = true;
    paintChips();
    showSkeletons();
    hideEmpty();
    ctx.status('lib-status').textContent = 'Searching…';
    try {
      const results = await api.searchTitles(q);
      if (gen !== libGen) return;
      loadedTitles = results;
      if (!(await repaintLoaded(gen))) return;
      titleCount = results.length;
      statusBase = results.length + ' results for “' + q + '”';
      renderStatus();
      syncFilterChrome();
      if (results.length === 0) setEmpty('search');
      else if (paintedCount === 0) setEmpty('filters');
      else hideEmpty();
      hideBanner();
    } catch {
      if (gen === libGen) {
        ctx.status('grid').removeAttribute('aria-busy');
        showBanner(
          'error',
          'Search failed — are the servers reachable?',
          'Retry',
          () => void runSearch(q),
        );
      }
    } finally {
      if (gen === libGen) pageLoading = false;
    }
  }
  const searchInput = document.getElementById(
    'lib-search',
  ) as HTMLInputElement;
  // Clear button + Ctrl-K hint visibility follows the field.
  function syncSearchChrome(): void {
    const hasText = searchInput.value.length > 0;
    ctx.status('lib-clear').classList.toggle('hidden', !hasText);
    document
      .querySelector('.search-hint')
      ?.classList.toggle('hidden', hasText);
  }
  function clearSearch(): void {
    window.clearTimeout(searchTimer);
    searchInput.value = '';
    syncSearchChrome();
    if (activeQuery !== '') void openFeed();
  }

  // The Servers probe coupling, formerly inlined in the shell.
  function probeStatus(up: number): void {
    if (titleCount === 0 && activeQuery === '') {
      ctx.status('lib-status').textContent =
        up > 0
          ? up + ' servers online'
          : 'No servers reachable — check the Servers tab';
    }
  }

  function mount(): () => void {
    const onBannerRetry = () => {
      const retry = bannerRetry;
      hideBanner();
      retry?.();
    };
    const onBannerClose = () => hideBanner();
    const onEmptyAction = () => {
      emptyAction?.();
    };
    // Poster failures are delegated (Agent 33): one capturing
    // listener instead of one per card.
    const onGridError = (e: Event) => {
      if (e.target instanceof HTMLImageElement) e.target.remove();
    };
    const onRefreshClick = async () => {
      const btn = ctx.status('lib-refresh') as HTMLButtonElement;
      btn.disabled = true;
      btn.classList.add('is-busy');
      btn.setAttribute('aria-busy', 'true');
      try {
        await loadLibrary(true);
      } finally {
        btn.disabled = false;
        btn.classList.remove('is-busy');
        btn.removeAttribute('aria-busy');
      }
    };
    const onClearClick = () => {
      clearSearch();
      searchInput.focus();
    };
    const onSearchInput = () => {
      syncSearchChrome();
      const q = searchInput.value.trim();
      window.clearTimeout(searchTimer);
      if (q.length === 0) {
        if (activeQuery !== '') void openFeed();
        return;
      }
      searchTimer = window.setTimeout(() => void runSearch(q), 300);
    };
    const onSearchKeys = (e: KeyboardEvent) => {
      if (e.key === 'Enter') {
        const q = searchInput.value.trim();
        window.clearTimeout(searchTimer);
        if (q) void runSearch(q);
      } else if (e.key === 'Escape') {
        clearSearch();
      }
    };

    ctx.status('lib-banner-retry').addEventListener('click', onBannerRetry);
    ctx.status('lib-banner-close').addEventListener('click', onBannerClose);
    ctx.status('lib-empty-action').addEventListener('click', onEmptyAction);
    const grid = ctx.status('grid');
    grid.addEventListener('error', onGridError, true);
    ctx.status('lib-refresh').addEventListener('click', onRefreshClick);
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) void loadMore();
      },
      { rootMargin: '600px' },
    );
    observer.observe(ctx.status('sentinel'));
    ctx.status('lib-clear').addEventListener('click', onClearClick);
    searchInput.addEventListener('input', onSearchInput);
    searchInput.addEventListener('keydown', onSearchKeys);
    const onFilterChange = () => {
      readFilterControls();
      void applyFiltersAndRepaint();
    };
    const onFilterClear = () => clearFilters();
    const mkSingle = (
      field: SingleField,
      mountId: string,
      label: string,
      options: { value: string; label: string }[],
      value: string,
    ) => {
      const dd = createDropdown({
        id: mountId + '-dd',
        label,
        options,
        value,
        onChange: onFilterChange,
      });
      filterDds[field] = dd;
      ctx.status(mountId).append(dd.root);
    };
    const mkMulti = (
      field: MultiField,
      mountId: string,
      label: string,
      options: { value: string; label: string }[],
    ) => {
      const md = createMultiDropdown({
        id: mountId + '-dd',
        label,
        options,
        values: [],
        onChange: onFilterChange,
      });
      filterMds[field] = md;
      ctx.status(mountId).append(md.root);
    };
    mkMulti('kinds', 'lib-filter-kind', 'Type', [
      { value: 'movie', label: 'Movies' },
      { value: 'series', label: 'TV Series' },
    ]);
    mkMulti('qualities', 'lib-filter-quality', 'Quality', []);
    mkMulti('languages', 'lib-filter-language', 'Language', []);
    mkMulti('sources', 'lib-filter-source', 'Server', []);
    mkMulti('sourceTags', 'lib-filter-tag', 'Release', []);
    mkSingle(
      'yearFrom',
      'lib-filter-year-from',
      'Filter by earliest year',
      [{ value: '', label: 'Year from' }],
      '',
    );
    mkSingle(
      'yearTo',
      'lib-filter-year-to',
      'Filter by latest year',
      [{ value: '', label: 'Year to' }],
      '',
    );
    mkSingle('size', 'lib-filter-size', 'Filter by minimum size', SIZE_PRESETS, '');
    mkSingle('sort', 'lib-filter-sort', 'Sort titles', [
      { value: 'feed', label: 'Sort: Newest' },
      { value: 'title', label: 'Sort: Title A–Z' },
      { value: 'year-desc', label: 'Sort: Year' },
      { value: 'size-desc', label: 'Sort: Size · largest' },
      { value: 'size-asc', label: 'Sort: Size · smallest' },
    ], 'feed');
    const dualBtn = ctx.status('lib-filter-dual');
    const onDualToggle = () => {
      dualOnly = !dualOnly;
      dualBtn.setAttribute('aria-pressed', String(dualOnly));
      dualBtn.classList.toggle('is-active', dualOnly);
      onFilterChange();
    };
    dualBtn.addEventListener('click', onDualToggle);
    ctx.status('lib-filter-clear').addEventListener('click', onFilterClear);
    syncFilterChrome();
    return () => {
      ctx
        .status('lib-banner-retry')
        .removeEventListener('click', onBannerRetry);
      ctx
        .status('lib-banner-close')
        .removeEventListener('click', onBannerClose);
      ctx
        .status('lib-empty-action')
        .removeEventListener('click', onEmptyAction);
      grid.removeEventListener('error', onGridError, true);
      ctx.status('lib-refresh').removeEventListener('click', onRefreshClick);
      observer.disconnect();
      ctx.status('lib-clear').removeEventListener('click', onClearClick);
      searchInput.removeEventListener('input', onSearchInput);
      searchInput.removeEventListener('keydown', onSearchKeys);
      for (const d of Object.values(filterDds)) d?.destroy();
      for (const d of Object.values(filterMds)) d?.destroy();
      dualBtn.removeEventListener('click', onDualToggle);
      ctx
        .status('lib-filter-clear')
        .removeEventListener('click', onFilterClear);
    };
  }

  return { loadLibrary, showBanner, probeStatus, mount };
}
