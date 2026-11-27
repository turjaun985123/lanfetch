// Pure-logic tests for the Detail file-action state matrix
// (FRONTEND.md §Testing): every download state maps to exactly
// one button presentation.
import { describe, expect, it } from 'vitest';
import { fileButtonKind } from './detail';

describe('fileButtonKind', () => {
  it('not-yet-downloaded files get the primary Download action', () => {
    expect(fileButtonKind(null, null)).toEqual({
      variant: 'primary',
      icon: 'download',
      label: 'Download',
      disabled: false,
    });
  });
  it('queued and running are disabled status buttons', () => {
    expect(fileButtonKind('queued', null)).toEqual({
      variant: 'ghost',
      icon: 'clock',
      label: 'Queued',
      disabled: true,
    });
    expect(fileButtonKind('running', 0.42)).toEqual({
      variant: 'ghost',
      icon: null,
      label: 'Downloading 42%',
      disabled: true,
    });
    expect(fileButtonKind('running', null).label).toBe('Downloading 0%');
  });
  it('paused resumes, failed retries (primary), completed is done', () => {
    expect(fileButtonKind('paused', 0.5)).toEqual({
      variant: 'ghost',
      icon: 'play',
      label: 'Resume',
      disabled: false,
    });
    expect(fileButtonKind('failed', null)).toEqual({
      variant: 'primary',
      icon: 'retry',
      label: 'Retry',
      disabled: false,
    });
    expect(fileButtonKind('completed', 1)).toEqual({
      variant: 'ghost',
      icon: 'check',
      label: 'Downloaded',
      disabled: true,
    });
  });
});
