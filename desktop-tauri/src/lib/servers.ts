// Servers screen (V2 Wave 4 step 2, docs/FRONTEND.md).
//
// Extracted verbatim from index.astro: the server list, the
// expanded-rail health list, probing, and the add-server form
// moved as-is, with only identifier qualifications (api. / ctx.
// prefixes). State (sourcesList, probeMap) lives in the factory
// closure. The one outward coupling the block had — writing the
// Library status line after a probe — goes through the ctx hook:
// the shell owns the Library's status element and its emptiness
// condition until Library itself extracts (step 4).
import * as api from './api';
import type { SourceDto, SourceProbeDto } from './api';
import { confirmDialog, el, icon, toast } from './ui';

export interface ServersCtx {
  /** The shell's element-by-id helper. */
  status: (id: string) => HTMLElement;
  /** After each probe round, let the shell update the Library
   * status line (it decides whether the line is the library's to
   * claim right now). */
  libraryStatus: (up: number) => void;
}

export interface ServersScreen {
  /** Install the source list (boot hands over bootstrap's). */
  setSources: (list: SourceDto[]) => void;
  renderServers: () => void;
  loadProbes: () => Promise<void>;
  /** Wire the add-server form and the refresh button. Returns
   * the dispose closure (FRONTEND.md disposal rules). */
  mount: () => () => void;
}

/** Add-server form validity (Agent 23's honest minimal rule):
 * non-empty name, non-empty whitespace-free address. No scheme
 * gate — a missing scheme earns a hint, not a block. Pure —
 * unit-tested. */
export function srvFormValid(name: string, url: string): boolean {
  const u = url.trim();
  return name.trim().length > 0 && u.length > 0 && !/\s/.test(u);
}

/** Whether the "missing scheme" hint shows: a non-empty address
 * with no `://` in it. Pure — unit-tested. */
export function srvSchemeHint(url: string): boolean {
  const u = url.trim();
  return u.length > 0 && !u.includes('://');
}

export function createServers(ctx: ServersCtx): ServersScreen {
  // ---- servers ----------------------------------------------------------
  let sourcesList: SourceDto[] = [];
  const probeMap = new Map<string, SourceProbeDto>();
  function srvError(msg: string): void {
    const e = ctx.status('srv-error');
    e.textContent = msg;
    e.classList.toggle('hidden', msg === '');
  }

  function renderServers(): void {
    const wrap = ctx.status('probes');
    wrap.replaceChildren();
    for (const s of sourcesList) {
      const p = probeMap.get(s.name);
      const row = el(
        'div',
        'row-card flex flex-wrap items-center gap-3 px-4 py-3',
      );
      const pill = el(
        'span',
        'badge border border-hairline bg-raised px-2 py-0.5 text-caption font-semibold text-ink-2',
      );
      const dot = el(
        'span',
        'status-dot' +
          (!s.enabled
            ? ' is-disabled'
            : p?.status === 'up'
              ? ' is-up'
              : p?.status === 'down'
                ? ' is-down'
                : ''),
      );
      pill.append(
        dot,
        document.createTextNode(
          !s.enabled
            ? 'Disabled'
            : p?.status === 'up'
              ? 'Online · ' + p.latencyMs + ' ms'
              : p?.status === 'down'
                ? 'Unreachable'
                : 'Checking…',
        ),
      );
      const name = el('span', 'text-row font-medium', s.name);
      const url = el(
        'span',
        'min-w-0 flex-1 truncate text-secondary text-ink-3',
        s.rootUrl,
      );
      url.title = s.rootUrl;
      const main = el('div', 'flex min-w-0 flex-1 items-center gap-3');
      main.append(pill, name, url);
      const toggle = document.createElement('input');
      toggle.type = 'checkbox';
      toggle.checked = s.enabled;
      toggle.className = 'switch';
      toggle.setAttribute(
        'aria-label',
        (s.enabled ? 'Disable ' : 'Enable ') + s.name,
      );
      toggle.addEventListener('change', async () => {
        try {
          sourcesList = await api.setSourceEnabled(s.name, toggle.checked);
          renderServers();
          void loadProbes();
        } catch {
          toggle.checked = !toggle.checked;
        }
      });
      row.append(main, toggle);
      if (s.custom) {
        const rm = document.createElement('button');
        rm.className = 'btn btn-danger btn-sm shrink-0';
        rm.replaceChildren(
          icon('trash', 16),
          document.createTextNode('Remove'),
        );
        rm.addEventListener('click', async () => {
          const ok = await confirmDialog({
            title: 'Remove this server?',
            body: '“' + s.name + '” will be removed from LanFetch.',
            confirmLabel: 'Remove',
          });
          if (!ok) return;
          try {
            sourcesList = await api.removeSource(s.name);
            probeMap.delete(s.name);
            renderServers();
            void loadProbes();
          } catch (e) {
            srvError(String(e));
          }
        });
        row.append(rm);
      }
      wrap.append(row);
    }
    renderRailServers();
  }

  // Expanded-rail server list (Agent 23): the same
  // sourcesList/probeMap, no new data path.
  function renderRailServers(): void {
    const host = ctx.status('rail-servers');
    host.replaceChildren();
    const up = sourcesList.filter(
      (s) => s.enabled && probeMap.get(s.name)?.status === 'up',
    ).length;
    for (const s of sourcesList.slice(0, 5)) {
      const p = probeMap.get(s.name);
      const row = el(
        'div',
        'flex items-center gap-2 px-1 py-0.5 text-secondary',
      );
      row.append(
        el(
          'span',
          'status-dot' +
            (!s.enabled
              ? ' is-disabled'
              : p?.status === 'up'
                ? ' is-up'
                : p?.status === 'down'
                  ? ' is-down'
                  : ''),
        ),
        el('span', 'min-w-0 flex-1 truncate', s.name),
      );
      if (s.enabled && p?.status === 'up') {
        row.append(
          el('span', 'tabular-nums text-ink-3', p.latencyMs + ' ms'),
        );
      }
      host.append(row);
    }
    host.append(
      el(
        'p',
        'px-1 pt-1 text-caption text-ink-3',
        up + ' of ' + sourcesList.length + ' online',
      ),
    );
  }
  async function loadProbes() {
    const results = await api.probeSources();
    probeMap.clear();
    for (const p of results) probeMap.set(p.name, p);
    renderServers();
    const up = results.filter((r) => r.status === 'up').length;
    ctx.status('srv-status').textContent =
      up + ' of ' + results.length + ' servers online';
    // Compact rail: server health survives as a dot on the
    // Servers nav item (Agent 12); words live in its label.
    const railDot = document.getElementById('rail-servers-dot');
    if (railDot) {
      railDot.className =
        'rail-dot status-dot' +
        (up > 0 ? ' is-up' : results.length > 0 ? ' is-down' : '');
    }
    document
      .querySelector('#nav .nav-item[data-view="servers"]')
      ?.setAttribute('aria-label', 'Servers, ' + up + ' online');
        ctx.libraryStatus(up);
  }
  // Add-server: honest minimal validation only (Agent 23) —
  // non-empty name, non-empty whitespace-free address. No
  // scheme or URL-parser gate: the engine accepts what it
  // accepts, and a missing scheme earns a hint, not a block.
  const srvNameEl = document.getElementById('srv-name') as HTMLInputElement;
  const srvUrlEl = document.getElementById('srv-url') as HTMLInputElement;
  const srvAddBtn = ctx.status('srv-add') as HTMLButtonElement;
  function validateSrvForm(): void {
    srvAddBtn.disabled = !srvFormValid(srvNameEl.value, srvUrlEl.value);
    ctx.status('srv-hint').classList.toggle(
      'hidden',
      !srvSchemeHint(srvUrlEl.value),
    );
  }

  function mount(): () => void {
    const refreshBtn = document.getElementById('srv-refresh')!;
    const onAddServer = async () => {
      srvError('');
      srvAddBtn.disabled = true;
      srvAddBtn.textContent = 'Adding…';
      try {
        sourcesList = await api.addSource(srvNameEl.value, srvUrlEl.value);
        const added = srvNameEl.value;
        srvNameEl.value = '';
        srvUrlEl.value = '';
        renderServers();
        void loadProbes();
        toast('Server ' + added + ' added');
      } catch (e) {
        srvError(String(e));
      } finally {
        srvAddBtn.textContent = 'Add server';
        validateSrvForm();
      }
    };
    const onRefreshServers = async () => {
      const btn = ctx.status('srv-refresh') as HTMLButtonElement;
      btn.disabled = true;
      btn.classList.add('is-busy');
      btn.setAttribute('aria-busy', 'true');
      try {
        await loadProbes();
      } finally {
        btn.disabled = false;
        btn.classList.remove('is-busy');
        btn.removeAttribute('aria-busy');
      }
    };
    srvNameEl.addEventListener('input', validateSrvForm);
    srvUrlEl.addEventListener('input', validateSrvForm);
    srvAddBtn.addEventListener('click', onAddServer);
    refreshBtn.addEventListener('click', onRefreshServers);
    return () => {
      srvNameEl.removeEventListener('input', validateSrvForm);
      srvUrlEl.removeEventListener('input', validateSrvForm);
      srvAddBtn.removeEventListener('click', onAddServer);
      refreshBtn.removeEventListener('click', onRefreshServers);
    };
  }

  function setSources(list: SourceDto[]): void {
    sourcesList = list;
  }

  return { setSources, renderServers, loadProbes, mount };
}
