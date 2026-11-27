// Pure-logic tests for the Settings derivations (FRONTEND.md
// §Testing): the persist merge and the theme resolution — the
// two computations every settings change flows through.
import { describe, expect, it } from 'vitest';
import type { SettingsDto } from './api';
import { mergeSettings, resolveTheme } from './settings';

const base: SettingsDto = {
  connections: 8,
  maxConcurrent: 3,
  theme: 'system',
  language: 'en',
  onboardingDone: false,
};

describe('mergeSettings', () => {
  it('applies a patch over the current settings', () => {
    expect(mergeSettings(base, { connections: 16 })).toEqual({
      ...base,
      connections: 16,
    });
    expect(mergeSettings(base, { theme: 'dark' }).theme).toBe('dark');
  });
  it('an empty patch returns an equal copy, not the same object', () => {
    const out = mergeSettings(base, {});
    expect(out).toEqual(base);
    expect(out).not.toBe(base);
  });
  it('does not mutate the current settings', () => {
    mergeSettings(base, { maxConcurrent: 9 });
    expect(base.maxConcurrent).toBe(3);
  });
});

describe('resolveTheme', () => {
  it('explicit choices win over the OS preference', () => {
    expect(resolveTheme('dark', false)).toBe('dark');
    expect(resolveTheme('dark', true)).toBe('dark');
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('light', false)).toBe('light');
  });
  it('system follows the OS preference', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });
  it('unknown choices fall back to light', () => {
    expect(resolveTheme('sepia', true)).toBe('light');
  });
});
