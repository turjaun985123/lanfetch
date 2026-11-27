// Pure formatting helpers (redesign Agent 28: extracted verbatim
// from index.astro in R2 — no behavior change). Stateless, DOM-free,
// unit-testable; the page and future screen modules import from here.
import type { TaskDto } from './api';

export function fmtBytes(n: number): string {
  if (n <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return (v >= 100 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
}

export const STATE_LABEL: Record<TaskDto['state'], string> = {
  queued: 'Queued',
  running: 'Running',
  paused: 'Paused',
  completed: 'Completed',
  failed: 'Failed',
};

export function fmtSpeed(bps: number): string {
  return bps > 0 ? fmtBytes(Math.round(bps)) + '/s' : '';
}

export function fmtEta(secs: number | null): string {
  if (secs == null || !isFinite(secs) || secs < 0) return '';
  const s = Math.round(secs);
  if (s < 60) return s + 's left';
  if (s < 3600) return Math.floor(s / 60) + 'm ' + (s % 60) + 's left';
  return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm left';
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('');
}

export function dotClass(s: string | undefined): string {
  return s === 'up' ? 'bg-done' : s === 'down' ? 'bg-failed' : 'bg-ink-3';
}
