// Pure-logic tests for the formatting helpers (FRONTEND.md
// §Testing). These pin the exact strings the Transfers UI shows.
import { describe, expect, it } from 'vitest';
import { fmtBytes, fmtEta, fmtSpeed, initials } from './format';

describe('fmtBytes', () => {
  it('renders zero and negatives as 0 B', () => {
    expect(fmtBytes(0)).toBe('0 B');
    expect(fmtBytes(-5)).toBe('0 B');
  });
  it('scales through units with one decimal under 100', () => {
    expect(fmtBytes(512)).toBe('512 B');
    expect(fmtBytes(1024)).toBe('1.0 KB');
    expect(fmtBytes(1536)).toBe('1.5 KB');
    expect(fmtBytes(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(fmtBytes(2.5 * 1024 * 1024 * 1024)).toBe('2.5 GB');
  });
  it('rounds at and above 100 of a unit', () => {
    expect(fmtBytes(250 * 1024)).toBe('250 KB');
  });
});

describe('fmtSpeed', () => {
  it('is empty when idle', () => {
    expect(fmtSpeed(0)).toBe('');
  });
  it('appends /s to a byte rate', () => {
    expect(fmtSpeed(2048)).toBe('2.0 KB/s');
  });
});

describe('fmtEta', () => {
  it('is empty for null, negative, or infinite input', () => {
    expect(fmtEta(null)).toBe('');
    expect(fmtEta(-1)).toBe('');
    expect(fmtEta(Infinity)).toBe('');
  });
  it('formats seconds, minutes, and hours', () => {
    expect(fmtEta(45)).toBe('45s left');
    expect(fmtEta(125)).toBe('2m 5s left');
    expect(fmtEta(3725)).toBe('1h 2m left');
  });
});

describe('initials', () => {
  it('takes the first letters of the first two words', () => {
    expect(initials('DHAKA-FLIX-9')).toBe('D');
    expect(initials('hello world again')).toBe('HW');
    expect(initials('  spaced   out ')).toBe('SO');
    expect(initials('')).toBe('');
  });
});
