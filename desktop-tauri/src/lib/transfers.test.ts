// Pure-logic tests for the Transfers derivations (FRONTEND.md
// §Testing): segment-bar geometry and the row-action signature —
// the two computations that decide when a keyed row re-renders
// its bar slices and rebuilds its action strip.
import { describe, expect, it } from 'vitest';
import type { TaskDto } from './api';
import { actionSignature, segFillPercents } from './transfers';

function task(over: Partial<TaskDto>): TaskDto {
  return {
    id: 1,
    url: 'http://example/f.mkv',
    name: 'f.mkv',
    state: 'running',
    total: 1000,
    downloaded: 0,
    fraction: 0,
    speedBps: 0,
    etaSecs: null,
    error: null,
    ready: true,
    fileMissing: false,
    segments: [],
    ...over,
  };
}

describe('segFillPercents', () => {
  it('is empty for an unsegmented task', () => {
    expect(segFillPercents(task({}))).toBe('');
  });
  it('computes per-segment percentages in order', () => {
    const t = task({
      segments: [
        { start: 0, end: 499, done: 500 },
        { start: 500, end: 999, done: 250 },
      ],
    });
    expect(segFillPercents(t)).toBe('100;50');
  });
  it('treats an open segment (end null) as ending at the total', () => {
    const t = task({
      total: 800,
      segments: [{ start: 0, end: null, done: 200 }],
    });
    expect(segFillPercents(t)).toBe('25');
  });
  it('clamps over- and under-run progress', () => {
    const t = task({
      segments: [
        { start: 0, end: 99, done: 250 },
        { start: 100, end: 199, done: -5 },
      ],
    });
    expect(segFillPercents(t)).toBe('100;0');
  });
  it('rounds to whole percents', () => {
    const t = task({
      total: 300,
      segments: [{ start: 0, end: 299, done: 100 }],
    });
    expect(segFillPercents(t)).toBe('33');
  });
});

describe('actionSignature', () => {
  it('combines state and the file-missing flag', () => {
    expect(actionSignature(task({ state: 'paused' }))).toBe('paused|false');
    expect(
      actionSignature(task({ state: 'completed', fileMissing: true })),
    ).toBe('completed|true');
  });
  it('changes exactly when the action strip must rebuild', () => {
    const a = actionSignature(task({ state: 'running' }));
    const b = actionSignature(task({ state: 'running', downloaded: 500 }));
    expect(a).toBe(b);
    expect(actionSignature(task({ state: 'failed' }))).not.toBe(a);
  });
});
