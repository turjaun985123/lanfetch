// Transfers screen (V2 Wave 4 step 1, docs/FRONTEND.md).
//
// Extracted verbatim from index.astro: the keyed-row DOM patching
// (the redesign's performance core) is unchanged — function bodies
// moved as-is, with only identifier qualifications (api. / ctx.
// prefixes). All mutable state lives in the factory closure; the
// stamp guard (lastStamp, dlFetching/dlDirty) moved with the
// screen, per the ownership rules. Cross-module surface is the
// ScreenCtx below plus the returned screen object — the shell
// aliases renderDownloads/refreshDownloads from it so the
// Detail/Library call sites did not change.
import * as api from './api';
import type { DownloadListDto, TaskDto } from './api';
import { fmtBytes, fmtEta, fmtSpeed, STATE_LABEL } from './format';
import { confirmDialog, el, icon, toast } from './ui';

export interface TransfersCtx {
  /** The shell's element-by-id helper. */
  status: (id: string) => HTMLElement;
  /** Called on every downloads-changed stamp, after Transfers'
   * own refresh — the shell forwards it to Detail's live file
   * refresh (a no-op unless Detail is open). */
  onStamp: () => void;
}

export interface TransfersScreen {
  renderDownloads: (list: DownloadListDto) => void;
  refreshDownloads: () => Promise<void>;
  /** Wire the downloads subscription and the toolbar buttons.
   * Returns the dispose closure (FRONTEND.md disposal rules). */
  mount: () => () => void;
}

/** Segment fill percentages as the patch signature string
 * ("0;50;100"): each segment's done fraction clamped to 0..1,
 * rounded to a whole percent. Pure — unit-tested. */
export function segFillPercents(t: TaskDto): string {
  return t.segments
    .map((s) => {
      const span = (s.end ?? t.total) - s.start;
      const f = span > 0 ? s.done / span : 0;
      return Math.round(Math.min(1, Math.max(0, f)) * 100);
    })
    .join(';');
}

/** The row-action signature: the action strip is rebuilt only
 * when this changes. Pure — unit-tested. */
export function actionSignature(t: TaskDto): string {
  return t.state + '|' + t.fileMissing;
}

export function createTransfers(ctx: TransfersCtx): TransfersScreen {
  // Transfers rows are KEYED (Agent 21): a row's DOM is built
  // once per task id and patched in place on every event —
  // the old full-rebuild per event was the screen's worst
  // problem (focus loss, flicker, layout thrash). Steady
  // state creates zero nodes; writes are guarded by the last
  // rendered values.
  interface TaskRow {
    root: HTMLElement;
    name: HTMLElement;
    state: HTMLElement;
    barWrap: HTMLElement;
    segbar: HTMLElement;
    simpleBar: HTMLElement;
    simpleFill: HTMLElement;
    meta: HTMLElement;
    error: HTMLElement;
    actions: HTMLElement;
    stateIconEl: HTMLElement;
    stateTextNode: Text;
    actionSig: string;
    lastName: string;
    lastStateText: string;
    lastStateIcon: string;
    lastMeta: string;
    lastError: string;
    lastPct: number;
    lastSegSig: string;
    lastSegFills: string;
    lastBarMode: string;
    lastSegCls: string;
  }
  const taskRows = new Map<number, TaskRow>();
  let trEmptyEl: HTMLElement | null = null;
  const prevTaskStates = new Map<number, TaskDto['state']>();

  // Screen-reader announcements (Agent 31): background
  // transitions only — never progress ticks, speeds or badge
  // counts. Click results are announced by their toasts.
  function announce(msg: string): void {
    const a = document.getElementById('a11y-announcer');
    if (!a) return;
    a.textContent = '';
    window.setTimeout(() => {
      a.textContent = msg;
    }, 40);
  }

  const STATE_ICON: Record<TaskDto['state'], string> = {
    queued: 'clock',
    running: 'download',
    paused: 'pause',
    completed: 'check',
    failed: 'warning',
  };

  function buildTaskRow(t: TaskDto): TaskRow {
    const root = el('div', 'card px-4 py-3.5');
    root.tabIndex = -1;
    const top = el('div', 'flex items-baseline gap-3');
    const name = el('span', 'min-w-0 flex-1 truncate text-row font-medium');
    name.title = t.name;
    const state = el(
      'span',
      'flex items-center gap-1.5 text-secondary font-medium text-ink-2',
    );
    // Persistent icon + text nodes (Agents 39/45): a percent
    // tick patches the text node only — zero node creation in
    // steady state.
    const stateIconEl = el('span', 'flex shrink-0');
    const stateTextNode = document.createTextNode('');
    state.append(stateIconEl, stateTextNode);
    top.append(name, state);
    const barWrap = el('div', 'mt-2.5');
    barWrap.setAttribute('role', 'progressbar');
    barWrap.setAttribute('aria-label', t.name);
    barWrap.setAttribute('aria-valuemin', '0');
    barWrap.setAttribute('aria-valuemax', '100');
    const segbar = el('div', 'segbar hidden');
    segbar.setAttribute('aria-hidden', 'true');
    const simpleBar = el('div', 'progress');
    const simpleFill = el('div', 'progress-fill');
    simpleBar.append(simpleFill);
    barWrap.append(segbar, simpleBar);
    const meta = el('p', 'mt-2 text-secondary tabular-nums text-ink-3');
    const error = el('p', 'task-error hidden');
    const actions = el('div', 'mt-2.5 flex gap-1.5');
    root.append(top, barWrap, meta, error, actions);
    return {
      root,
      name,
      state,
      barWrap,
      segbar,
      simpleBar,
      simpleFill,
      meta,
      error,
      actions,
      stateIconEl,
      stateTextNode,
      actionSig: '',
      lastName: '',
      lastStateText: '',
      lastStateIcon: '',
      lastMeta: '',
      lastError: '',
      lastPct: -1,
      lastSegSig: '',
      lastSegFills: '',
      lastBarMode: '',
      lastSegCls: '',
    };
  }

  function taskActionBtn(
    label: string,
    iconName: string,
    danger: boolean,
    fn: () => Promise<DownloadListDto>,
    taskName: string,
  ): HTMLButtonElement {
    const b = document.createElement('button');
    b.className = 'btn btn-sm ' + (danger ? 'btn-danger' : 'btn-ghost');
    b.dataset.act = label;
    // Row actions carry the task name for AT (Agent 37).
    b.setAttribute('aria-label', label + ' ' + taskName);
    b.replaceChildren(icon(iconName, 16), document.createTextNode(label));
    b.addEventListener('click', async () => {
      b.disabled = true;
      try {
        renderDownloads(await fn());
      } catch {
        b.disabled = false;
        toast('That action failed — the list has been refreshed.', 'alert');
        void refreshDownloads();
      }
    });
    return b;
  }

  function patchTaskRow(row: TaskRow, t: TaskDto): void {
    if (row.lastName !== t.name) {
      row.lastName = t.name;
      row.name.textContent = t.name;
      row.name.title = t.name;
      row.barWrap.setAttribute('aria-label', t.name);
    }
    const pct = Math.round(t.fraction * 100);
    const stateText =
      STATE_LABEL[t.state] +
      (t.state === 'running' || t.state === 'paused' ? ' · ' + pct + '%' : '');
    if (row.lastStateIcon !== STATE_ICON[t.state]) {
      row.lastStateIcon = STATE_ICON[t.state];
      row.stateIconEl.replaceChildren(icon(STATE_ICON[t.state], 16));
    }
    if (row.lastStateText !== stateText) {
      row.lastStateText = stateText;
      row.stateTextNode.data = stateText;
    }
    if (row.lastPct !== pct) {
      row.lastPct = pct;
      row.barWrap.setAttribute('aria-valuenow', String(pct));
      row.barWrap.setAttribute(
        'aria-valuetext',
        STATE_LABEL[t.state] + ', ' + pct + ' percent',
      );
    }
    // Segmented vs simple bar (Agent 22): segments render when
    // the engine reports them against a known total; the
    // simple bar covers everything else.
    const segmented = t.segments.length > 0 && t.total > 0;
    const barMode = segmented ? 'seg' : 'simple';
    if (row.lastBarMode !== barMode) {
      row.lastBarMode = barMode;
      row.segbar.classList.toggle('hidden', !segmented);
      row.simpleBar.classList.toggle('hidden', segmented);
    }
    if (segmented) {
      const segCls =
        'segbar' +
        (t.state === 'paused'
          ? ' is-paused'
          : t.state === 'failed'
            ? ' is-failed'
            : t.state === 'completed'
              ? ' is-done'
              : '');
      if (row.lastSegCls !== segCls) {
        row.lastSegCls = segCls;
        row.segbar.className = segCls;
      }
      const geoSig = t.segments
        .map((s) => s.start + ':' + (s.end ?? t.total))
        .join(';');
      if (row.lastSegSig !== geoSig) {
        row.lastSegSig = geoSig;
        row.lastSegFills = '';
        row.segbar.replaceChildren();
        for (const s of t.segments) {
          const slice = el('div', 'segbar-slice');
          slice.style.left = (s.start / t.total) * 100 + '%';
          slice.style.width =
            (((s.end ?? t.total) - s.start) / t.total) * 100 + '%';
          slice.append(el('div', 'segbar-fill'));
          row.segbar.append(slice);
        }
      }
      const fills = segFillPercents(t);
      if (row.lastSegFills !== fills) {
        row.lastSegFills = fills;
        const parts = fills.split(';');
        row.segbar.querySelectorAll('.segbar-fill').forEach((fill, i) => {
          (fill as HTMLElement).style.width = (parts[i] ?? '0') + '%';
        });
      }
    } else {
      if (row.simpleFill.style.width !== pct + '%') {
        row.simpleFill.style.width = pct + '%';
      }
      // The state colour patches independently of the width
      // (Agent 39): pausing freezes the fraction, so nesting
      // this inside the width guard left a paused bar wearing
      // the running colour forever.
      const fillCls =
        'progress-fill' +
        (t.state === 'paused'
          ? ' is-paused'
          : t.state === 'failed'
            ? ' is-failed'
            : t.state === 'completed'
              ? ' is-done'
              : '');
      if (row.simpleFill.className !== fillCls) {
        row.simpleFill.className = fillCls;
      }
    }
    const metaText =
      fmtBytes(t.downloaded) +
      (t.total > 0 ? ' of ' + fmtBytes(t.total) : '') +
      (t.state === 'running' && t.speedBps > 0
        ? ' · ' + fmtSpeed(t.speedBps)
        : '') +
      (t.state === 'running' && fmtEta(t.etaSecs)
        ? ' · ' + fmtEta(t.etaSecs)
        : '');
    if (row.lastMeta !== metaText) {
      row.lastMeta = metaText;
      row.meta.textContent = metaText;
    }
    // Errors get their own line, never appended to the meta
    // line (Agent 22).
    const errText = t.state === 'failed' && t.error ? t.error : '';
    if (row.lastError !== errText) {
      row.lastError = errText;
      row.error.textContent = errText;
      row.error.classList.toggle('hidden', errText === '');
    }
    const actionSig = actionSignature(t);
    if (row.actionSig !== actionSig) {
      row.actionSig = actionSig;
      // Rebuilding the buttons deletes the focused one
      // (Agent 39): remember which action held focus and
      // restore it to the equivalent new button afterwards.
      const ae = document.activeElement as HTMLElement | null;
      const focusAct =
        ae && row.actions.contains(ae) ? ae.dataset.act : undefined;
      row.actions.replaceChildren();
      if (t.state === 'running' || t.state === 'queued') {
        row.actions.append(
          taskActionBtn('Pause', 'pause', false, () => api.pauseDownload(t.id), t.name),
        );
      }
      if (t.state === 'paused') {
        row.actions.append(
          taskActionBtn('Resume', 'play', false, () => api.resumeDownload(t.id), t.name),
        );
      }
      if (t.state === 'failed') {
        row.actions.append(
          taskActionBtn('Retry', 'retry', false, () => api.resumeDownload(t.id), t.name),
        );
      }
      if (t.state === 'completed' && t.fileMissing) {
        const note = el(
          'span',
          'self-center text-secondary text-paused',
          'File missing',
        );
        row.actions.append(
          note,
          taskActionBtn('Redownload', 'redownload', false, () =>
            api.redownloadDownload(t.id), t.name,
          ),
        );
      }
      // Remove is destructive: it goes through the confirm
      // dialog, not the plain action path (Agent 02 #7).
      const rm = document.createElement('button');
      rm.className = 'btn btn-sm btn-danger';
      rm.dataset.act = 'Remove';
      rm.setAttribute('aria-label', 'Remove ' + t.name);
      rm.replaceChildren(icon('trash', 16), document.createTextNode('Remove'));
      rm.addEventListener('click', async () => {
        const ok = await confirmDialog({
          title: 'Remove this download?',
          body: '“' + t.name + '” will be removed from Transfers.',
          confirmLabel: 'Remove',
        });
        if (!ok) return;
        rm.disabled = true;
        try {
          renderDownloads(await api.removeDownload(t.id));
        } catch {
          rm.disabled = false;
          toast('Remove failed — the list has been refreshed.', 'alert');
          void refreshDownloads();
        }
      });
      row.actions.append(rm);
      if (focusAct) {
        const next = row.actions.querySelector(
          '[data-act="' + focusAct + '"]',
        ) as HTMLElement | null;
        (next ?? row.root).focus();
      }
    }
  }

  function renderDownloads(list: DownloadListDto) {
    // A stale list (older stamp arriving late) never renders
    // (Agent 39) — it could regress state and re-announce.
    if (list.stamp < lastStamp) return;
    lastStamp = list.stamp;
    const wrap = ctx.status('tasks');
    const tasks = list.tasks;
    ctx.status('tr-status').textContent =
      tasks.length === 0
        ? 'No transfers yet'
        : tasks.length + ' transfers · ' + list.activeCount + ' active';
    const badge = ctx.status('nav-badge');
    if (list.activeCount > 0) {
      badge.textContent = String(list.activeCount);
      badge.classList.remove('hidden');
    } else {
      badge.classList.add('hidden');
    }
    // The badge numeral is decoration for AT (Agent 31): the
    // count lives in the Transfers button's accessible name.
    document
      .querySelector('#nav .nav-item[data-view="transfers"]')
      ?.setAttribute(
        'aria-label',
        list.activeCount > 0
          ? 'Transfers, ' + list.activeCount + ' active downloads'
          : 'Transfers',
      );
    // Summary strip (Agent 21) from the DTO already in hand.
    const summary = ctx.status('tr-summary');
    summary.classList.toggle('hidden', tasks.length === 0);
    if (tasks.length > 0) {
      ctx.status('tr-stat-active').textContent = String(list.activeCount);
      const speed = tasks
        .filter((t) => t.state === 'running')
        .reduce((sum, t) => sum + t.speedBps, 0);
      ctx.status('tr-stat-speed').textContent =
        speed > 0 ? fmtSpeed(speed) + ' total' : '';
      ctx.status('tr-stat-queued').textContent = String(
        tasks.filter((t) => t.state === 'queued').length,
      );
      ctx.status('tr-stat-completed').textContent = String(
        tasks.filter((t) => t.state === 'completed').length,
      );
      ctx.status('tr-stat-failed').textContent = String(
        tasks.filter((t) => t.state === 'failed').length,
      );
    }
    for (const id of ['tr-pause-all', 'tr-resume-all', 'tr-clear']) {
      (ctx.status(id) as HTMLButtonElement).disabled = tasks.length === 0;
    }
    // Empty state: created once, toggled (Agents 21/25).
    if (!trEmptyEl) {
      trEmptyEl = el('div', 'empty-state');
      trEmptyEl.setAttribute('role', 'status');
      const ic = el('span', 'text-ink-3');
      ic.append(icon('transfers', 24));
      trEmptyEl.append(
        ic,
        el('h3', 'mt-3 text-heading font-semibold', 'No transfers yet'),
        el(
          'p',
          'mt-1 max-w-[420px] text-body text-ink-2',
          'Movies and episodes you download appear here with live progress and speed.',
        ),
      );
    }
    trEmptyEl.classList.toggle('hidden', tasks.length !== 0);
    // Keyed rows in list order. An already-ordered render
    // moves nothing (Agent 39): a node is only re-inserted
    // when its predecessor is wrong.
    const seen = new Set<number>();
    for (const t of tasks) {
      seen.add(t.id);
      if (!taskRows.has(t.id)) taskRows.set(t.id, buildTaskRow(t));
      patchTaskRow(taskRows.get(t.id)!, t);
    }
    let anchor: ChildNode | null = null;
    const ordered: HTMLElement[] = [
      trEmptyEl,
      ...tasks.map((t) => taskRows.get(t.id)!.root),
    ];
    for (const node of ordered) {
      if (node.parentNode !== wrap || node.previousSibling !== anchor) {
        wrap.insertBefore(node, anchor ? anchor.nextSibling : wrap.firstChild);
      }
      anchor = node;
    }
    for (const [id, row] of taskRows) {
      if (!seen.has(id)) {
        row.root.remove();
        taskRows.delete(id);
      }
    }
    // Announce background state transitions (Agent 31). The
    // first render seeds the map silently — tasks that were
    // already completed at boot are not news. Simultaneous
    // transitions join into one announcement so none is
    // overwritten (Agent 39).
    const notes: string[] = [];
    for (const t of tasks) {
      const prev = prevTaskStates.get(t.id);
      if (prev !== undefined && prev !== t.state) {
        if (t.state === 'completed')
          notes.push('Download complete: ' + t.name);
        else if (t.state === 'failed')
          notes.push('Download failed: ' + t.name);
      }
      prevTaskStates.set(t.id, t.state);
    }
    if (notes.length > 0) announce(notes.join(' '));
    for (const id of [...prevTaskStates.keys()]) {
      if (!seen.has(id)) prevTaskStates.delete(id);
    }
  }

  // ---- live transfers: pushed, never polled --------------------------------
  let lastStamp = 0;
  let dlFetching = false;
  let dlDirty = false;
  async function refreshDownloads(): Promise<void> {
    if (dlFetching) {
      dlDirty = true;
      return;
    }
    dlFetching = true;
    try {
      const list = await api.getDownloads();
      if (list.stamp >= lastStamp) {
        lastStamp = list.stamp;
        renderDownloads(list);
      }
    } catch {
      // Not in Tauri (browser preview) or a transient IPC error:
      // the next event retries.
    } finally {
      dlFetching = false;
      if (dlDirty) {
        dlDirty = false;
        void refreshDownloads();
      }
    }
  }

  function mount(): () => void {
    const unsubscribe = api.onDownloadsChanged((stamp) => {
      if (stamp > lastStamp) void refreshDownloads();
      // Keep an open Detail page's file states live (Agent 20);
      // the shell's hook no-ops unless Detail is visible and
      // re-renders only on a real signature change.
      ctx.onStamp();
    });
    const clearBtn = document.getElementById('tr-clear')!;
    const pauseAllBtn = document.getElementById('tr-pause-all')!;
    const resumeAllBtn = document.getElementById('tr-resume-all')!;
    const onClear = async () => {
      const ok = await confirmDialog({
        title: 'Clear completed downloads?',
        body: 'Completed downloads will be removed from the Transfers list.',
        confirmLabel: 'Clear',
      });
      if (!ok) return;
      try {
        renderDownloads(await api.clearCompletedDownloads());
      } catch {}
    };
    const onPauseAll = async () => {
      try {
        renderDownloads(await api.pauseAllDownloads());
      } catch {}
    };
    const onResumeAll = async () => {
      try {
        renderDownloads(await api.resumeAllDownloads());
      } catch {}
    };
    clearBtn.addEventListener('click', onClear);
    pauseAllBtn.addEventListener('click', onPauseAll);
    resumeAllBtn.addEventListener('click', onResumeAll);
    return () => {
      unsubscribe();
      clearBtn.removeEventListener('click', onClear);
      pauseAllBtn.removeEventListener('click', onPauseAll);
      resumeAllBtn.removeEventListener('click', onResumeAll);
    };
  }

  return { renderDownloads, refreshDownloads, mount };
}
