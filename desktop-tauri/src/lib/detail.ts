// Title detail screen (V2 Wave 4 step 5, docs/FRONTEND.md) —
// the last screen extracted; the page script is now shell, boot
// and shared helpers only.
//
// Extracted verbatim from index.astro with only identifier
// qualifications (api. / ctx. prefixes). State (detailGen,
// detailTitle, the Agent 40 click/refresh interlocks) lives in
// the factory closure. Cross-module traffic: ctx.show for view
// switching, ctx.renderDownloads for the shell's Transfers
// alias; the Transfers stamp callback reaches back through the
// exported refreshDetailFiles, and the Library reaches in
// through the exported openDetail. Wiring lives in mount() as
// named handlers; dispose removes them all.
import * as api from './api';
import type {
  DownloadListDto,
  FileDto,
  TitleDetailDto,
  TitleDto,
} from './api';
import { fmtBytes, initials } from './format';
import { el, icon } from './ui';

export interface DetailCtx {
  /** The shell's element-by-id helper. */
  status: (id: string) => HTMLElement;
  /** Switch the visible view (shell-owned). */
  show: (view: string) => void;
  /** Repaint the transfers list (the Transfers screen's render,
   * aliased by the shell). */
  renderDownloads: (list: DownloadListDto) => void;
}

export interface DetailScreen {
  openDetail: (t: TitleDto) => Promise<void>;
  /** Leave Detail, restoring focus to the invoking card (the
   * shell's Ctrl-K path uses this too, Agent 49). */
  closeDetail: () => void;
  /** Re-fetch the open title's files after a downloads change
   * (signature-gated; never clobbers an in-flight file click). */
  refreshDetailFiles: () => Promise<void>;
  /** Wire back / Escape / favorite handlers. Returns the
   * dispose closure (FRONTEND.md disposal rules). */
  mount: () => () => void;
}

export interface FileButtonKind {
  variant: 'ghost' | 'primary';
  /** Icon name, or null for a text-only button (running). */
  icon: string | null;
  label: string;
  disabled: boolean;
}

/** The file-action state matrix (Agent 20, FRONTEND.md's named
 * test target): what a file row's button looks like for each
 * download state. Pure — unit-tested. */
export function fileButtonKind(
  state: FileDto['downloadState'],
  fraction: number | null,
): FileButtonKind {
  switch (state) {
    case 'queued':
      return { variant: 'ghost', icon: 'clock', label: 'Queued', disabled: true };
    case 'running':
      return {
        variant: 'ghost',
        icon: null,
        label: 'Downloading ' + Math.round((fraction ?? 0) * 100) + '%',
        disabled: true,
      };
    case 'paused':
      return { variant: 'ghost', icon: 'play', label: 'Resume', disabled: false };
    case 'completed':
      return { variant: 'ghost', icon: 'check', label: 'Downloaded', disabled: true };
    case 'failed':
      return { variant: 'primary', icon: 'retry', label: 'Retry', disabled: false };
    default:
      return { variant: 'primary', icon: 'download', label: 'Download', disabled: false };
  }
}

export function createDetail(ctx: DetailCtx): DetailScreen {
  // ---- title detail ---------------------------------------------------------
  let detailGen = 0;
  let detailTitle: TitleDto | null = null;

  // File actions (Agent 20). The old Paused button was a
  // disabled dead-end: start_download does NOT resume a paused
  // task (verified in engine manager.rs — enqueue dedups by
  // URL and silently returns). A real resume finds the task by
  // URL and calls resumeDownload; failed tasks resume only
  // when the engine says the partial is ready, otherwise they
  // re-probe through startDownload.
  const pendingFileClicks = new Set<string>();
  function fileButton(f: FileDto): HTMLButtonElement {
    const b = document.createElement('button');
    const paint = () => {
      const kind = fileButtonKind(f.downloadState, f.downloadFraction);
      b.className =
        kind.variant === 'primary'
          ? 'btn btn-primary btn-sm shrink-0'
          : 'btn btn-ghost btn-sm shrink-0';
      if (kind.icon) {
        b.replaceChildren(
          icon(kind.icon, 16),
          document.createTextNode(kind.label),
        );
      } else {
        b.textContent = kind.label;
      }
      b.disabled = kind.disabled;
      // The accessible name carries the file (Agent 37).
      b.setAttribute(
        'aria-label',
        (b.textContent ?? '') + ' — ' + f.fileName,
      );
    };
    paint();
    b.addEventListener('click', async () => {
      if (pendingFileClicks.has(f.fileUrl)) return;
      pendingFileClicks.add(f.fileUrl);
      b.disabled = true;
      b.textContent = 'Working…';
      try {
        let acted = false;
        if (f.downloadState === 'paused' || f.downloadState === 'failed') {
          try {
            const list = await api.getDownloads();
            const task = list.tasks.find((x) => x.url === f.fileUrl);
            if (task && (f.downloadState === 'paused' || task.ready)) {
              ctx.renderDownloads(await api.resumeDownload(task.id));
              acted = true;
            }
          } catch {
            // fall through to a fresh startDownload
          }
        }
        if (!acted) {
          ctx.renderDownloads(await api.startDownload(f.fileUrl, f.fileName));
        }
        f.downloadState = 'queued';
        f.downloadFraction = 0;
        paint();
        void refreshDetailFiles();
      } catch {
        paint();
      } finally {
        pendingFileClicks.delete(f.fileUrl);
        // Drain any refresh that deferred while this click
        // was in flight (Agent 40).
        void refreshDetailFiles();
      }
    });
    return b;
  }

  function renderFiles(detail: TitleDetailDto): void {
    const wrap = ctx.status('detail-files');
    wrap.replaceChildren();
    if (detail.files.length === 0) {
      const empty = el('div', 'empty-state');
      empty.setAttribute('role', 'status');
      const ic = el('span', 'text-ink-3');
      ic.append(icon('film', 24));
      const h = el('h4', 'mt-3 text-heading font-semibold', 'No downloadable files');
      const p = el(
        'p',
        'mt-1 max-w-[420px] text-body text-ink-2',
        'This folder holds no video files LanFetch can fetch.',
      );
      empty.append(ic, h, p);
      wrap.append(empty);
      return;
    }
    const isSeries = detail.title.kind === 'series';
    const seasons = new Map<number, FileDto[]>();
    for (const f of detail.files) {
      const key = isSeries ? (f.season ?? 0) : 0;
      const arr = seasons.get(key) ?? [];
      arr.push(f);
      seasons.set(key, arr);
    }
    for (const season of [...seasons.keys()].sort((a, b) => a - b)) {
      const files = seasons.get(season)!;
      if (isSeries) {
        const head = el('div', 'mt-2 flex items-center justify-between gap-2');
        head.append(
          el(
            'span',
            'section-label',
            (season > 0 ? 'Season ' + season : 'Episodes') +
              ' · ' +
              files.length +
              ' files',
          ),
        );
        const actionable = files.filter(
          (f) => !f.downloadState || f.downloadState === 'failed',
        );
        if (files.length >= 2 && actionable.length >= 2) {
          const all = el('button', 'btn btn-ghost btn-sm', 'Download all');
          all.addEventListener('click', async () => {
            all.disabled = true;
            all.textContent = 'Queueing…';
            let last: DownloadListDto | null = null;
            try {
              for (const f of actionable) {
                try {
                  last = await api.startDownload(f.fileUrl, f.fileName);
                } catch {
                  // one bad file must not stop the season
                }
              }
              if (last) ctx.renderDownloads(last);
            } finally {
              // If every start failed, restore the button —
              // otherwise it wedges on "Queueing…" until the
              // next re-render (Agent 40).
              if (!last) {
                all.disabled = false;
                all.textContent = 'Download all';
              }
              void refreshDetailFiles();
            }
          });
          head.append(all);
        }
        wrap.append(head);
      }
      for (const f of files) {
        const row = el(
          'div',
          'row-card relative flex items-center gap-3 overflow-hidden px-3.5 py-3',
        );
        row.dataset.fileUrl = f.fileUrl;
        const text = el('div', 'min-w-0 flex-1');
        const name = el('p', 'truncate text-row font-medium', f.fileName);
        name.title = f.fileName;
        const sub = el(
          'div',
          'mt-0.5 flex items-center gap-1.5 text-secondary tabular-nums text-ink-3',
        );
        sub.append(
          document.createTextNode(
            (f.episode != null ? 'E' + f.episode + ' · ' : '') +
              (f.size >= 0 ? fmtBytes(f.size) : 'Size unknown') +
              (f.quality ? ' · ' + f.quality : '') +
              (f.codec ? ' · ' + f.codec : '') +
              (f.audio ? ' · ' + f.audio : ''),
          ),
        );
        if (f.sourceTag) {
          sub.append(
            el(
              'span',
              'badge border border-hairline bg-raised px-1.5 py-0.5 text-caption font-medium text-ink-2',
              f.sourceTag,
            ),
          );
        }
        text.append(name, sub);
        row.append(text, fileButton(f));
        if (f.downloadState === 'running') {
          const bar = el('div', 'file-progress');
          bar.style.width =
            Math.round((f.downloadFraction ?? 0) * 100) + '%';
          row.append(bar);
        }
        wrap.append(row);
      }
    }
  }

  // Live detail refresh (Agent 20): downloads-changed events
  // re-fetch the open title, but the file list re-renders only
  // when its signature actually changed — no flicker, no lost
  // scroll position, no clobbering a click in flight.
  let detailFilesSig = '';
  let detailRefreshInFlight = false;
  let detailDirty = false;
  function filesSignature(files: FileDto[]): string {
    return files
      .map(
        (f) =>
          f.fileUrl +
          '|' +
          (f.downloadState ?? '') +
          '|' +
          Math.round((f.downloadFraction ?? 0) * 100),
      )
      .join(';');
  }
  async function refreshDetailFiles(): Promise<void> {
    const section = document.querySelector('[data-screen="detail"]');
    if (!detailTitle || !section || section.classList.contains('hidden'))
      return;
    // Never clobber an in-flight file click (Agent 40): the
    // click's finally re-invokes this once it settles.
    if (pendingFileClicks.size > 0) {
      detailDirty = true;
      return;
    }
    if (detailRefreshInFlight) {
      detailDirty = true;
      return;
    }
    detailRefreshInFlight = true;
    const gen = detailGen;
    try {
      const detail = await api.getTitleDetail(detailTitle.folderUrl);
      if (gen !== detailGen) return;
      const sig = filesSignature(detail.files);
      if (sig === detailFilesSig) return;
      detailFilesSig = sig;
      detailTitle = detail.title;
      ctx.status('detail-files-heading').textContent =
        'Files · ' + detail.files.length;
      const main = document.querySelector('main');
      const top = main?.scrollTop ?? 0;
      // renderFiles rebuilds the rows: if focus sat on a
      // file button, restore it to the same file's new
      // button afterwards (Agent 40).
      const ae = document.activeElement as HTMLElement | null;
      const focusRow = ae?.closest('[data-file-url]') as
        | HTMLElement
        | null;
      const focusUrl =
        focusRow && ctx.status('detail-files').contains(focusRow)
          ? focusRow.dataset.fileUrl
          : undefined;
      renderFiles(detail);
      if (main) main.scrollTop = top;
      if (focusUrl) {
        (
          ctx
            .status('detail-files')
            .querySelector(
              '[data-file-url="' + CSS.escape(focusUrl) + '"] button',
            ) as HTMLElement | null
        )?.focus({ preventScroll: true });
      }
    } catch {
      // keep the current paint; the next event retries
    } finally {
      detailRefreshInFlight = false;
      if (detailDirty) {
        detailDirty = false;
        void refreshDetailFiles();
      }
    }
  }

  function paintFav(): void {
    const fav = detailTitle?.isFavorite ?? false;
    ctx.status('detail-fav').setAttribute('aria-pressed', String(fav));
    ctx.status('detail-fav-icon').replaceChildren(
      icon(fav ? 'star-filled' : 'star', 16),
    );
    ctx.status('detail-fav-label').textContent = fav ? 'Favorited' : 'Favorite';
  }

  // Focus path (Agents 31/34): opening Detail moves focus to
  // the title; closing returns it to the card that opened it.
  let detailInvoker: HTMLElement | null = null;
  function closeDetail(): void {
    ctx.show('library');
    const invoker = detailInvoker;
    detailInvoker = null;
    if (invoker && document.contains(invoker)) {
      invoker.focus();
    } else {
      (
        document.querySelector(
          '#chips .chip[tabindex="0"]',
        ) as HTMLElement | null
      )?.focus();
    }
  }

  async function openDetail(t: TitleDto): Promise<void> {
    const gen = ++detailGen;
    detailTitle = t;
    detailFilesSig = '';
    if (
      document.activeElement instanceof HTMLElement &&
      document.activeElement !== document.body
    ) {
      detailInvoker = document.activeElement;
    }
    ctx.show('detail');
    ctx.status('detail-title').focus({ preventScroll: true });
    // Snapshot paint: everything the DTO already knows renders
    // now; only the file list waits for hydration.
    ctx.status('detail-title').textContent = t.title;
    ctx.status('detail-title').title = t.title;
    const meta = ctx.status('detail-meta');
    meta.replaceChildren();
    const chips: string[] = [];
    if (t.year) chips.push(String(t.year));
    chips.push(t.category, t.sourceName);
    if (t.language) chips.push(t.language);
    if (t.copies > 1) chips.push(t.copies + ' copies');
    for (const c of chips) meta.append(el('span', 'badge-meta', c));
    ctx.status('detail-initials').textContent = initials(t.title);
    const poster = ctx.status('detail-poster') as HTMLImageElement;
    const wash = ctx.status('detail-wash') as HTMLImageElement;
    poster.classList.add('hidden');
    poster.removeAttribute('src');
    wash.classList.add('hidden');
    wash.removeAttribute('src');
    if (t.posterUrl) {
      const [src] = await api.posterSrcs([t.posterUrl]);
      if (gen === detailGen && src) {
        // Property assignment, not addEventListener: these
        // elements persist across Detail visits and listeners
        // would accumulate (Agent 45).
        poster.onerror = () => poster.classList.add('hidden');
        poster.src = src;
        poster.classList.remove('hidden');
        // The hero wash reuses the same resolved poster src —
        // the frontend never constructs poster URLs (Agent 30).
        wash.onerror = () => wash.classList.add('hidden');
        wash.src = src;
        wash.classList.remove('hidden');
      }
    }
    paintFav();
    ctx.status('detail-status').textContent = 'Loading files…';
    ctx.status('detail-files-heading').textContent = 'Files';
    ctx.status('detail-files').replaceChildren();
    try {
      const detail = await api.getTitleDetail(t.folderUrl);
      if (gen !== detailGen) return;
      detailTitle = detail.title;
      detailFilesSig = filesSignature(detail.files);
      ctx.status('detail-status').textContent = '';
      ctx.status('detail-files-heading').textContent =
        'Files · ' + detail.files.length;
      renderFiles(detail);
    } catch {
      if (gen === detailGen) {
        ctx.status('detail-status').textContent =
          'Could not load the file list — the server may be unreachable.';
      }
    }
  }

  function mount(): () => void {
    const onBackClick = () => closeDetail();
    const onDocKeys = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const detail = document.querySelector('[data-screen="detail"]')!;
      if (!detail.classList.contains('hidden')) closeDetail();
    };
    const onFavClick = async () => {
      if (!detailTitle) return;
      const now = await api.toggleFavorite(detailTitle.folderUrl);
      detailTitle.isFavorite = now;
      paintFav();
      if (now) {
        const ic = ctx.status('detail-fav-icon');
        ic.classList.remove('fav-pop');
        void ic.offsetWidth;
        ic.classList.add('fav-pop');
      }
    };
    const back = document.getElementById('detail-back')!;
    back.addEventListener('click', onBackClick);
    document.addEventListener('keydown', onDocKeys);
    ctx.status('detail-fav').addEventListener('click', onFavClick);
    return () => {
      back.removeEventListener('click', onBackClick);
      document.removeEventListener('keydown', onDocKeys);
      ctx.status('detail-fav').removeEventListener('click', onFavClick);
    };
  }

  return { openDetail, closeDetail, refreshDetailFiles, mount };
}
