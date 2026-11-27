// Pure-logic tests for the Servers form rules (FRONTEND.md
// §Testing): the add-server validation and the scheme hint —
// Agent 23's "honest minimal validation", pinned so a future
// edit cannot quietly grow a scheme gate.
import { describe, expect, it } from 'vitest';
import { srvFormValid, srvSchemeHint } from './servers';

describe('srvFormValid', () => {
  it('accepts a name and a whitespace-free address', () => {
    expect(srvFormValid('DHAKA-FLIX-9', 'http://172.16.50.9/')).toBe(true);
    expect(srvFormValid('My Server', '172.16.50.9/share')).toBe(true);
  });
  it('rejects empty or whitespace-only fields', () => {
    expect(srvFormValid('', 'http://x/')).toBe(false);
    expect(srvFormValid('   ', 'http://x/')).toBe(false);
    expect(srvFormValid('Name', '')).toBe(false);
    expect(srvFormValid('Name', '   ')).toBe(false);
  });
  it('rejects whitespace inside the address', () => {
    expect(srvFormValid('Name', 'http://exa mple/')).toBe(false);
    expect(srvFormValid('Name', ' http://x/ ')).toBe(true);
  });
  it('does not require a scheme', () => {
    expect(srvFormValid('Name', 'example.local/share')).toBe(true);
  });
});

describe('srvSchemeHint', () => {
  it('shows for a non-empty address without a scheme', () => {
    expect(srvSchemeHint('172.16.50.9/share')).toBe(true);
    expect(srvSchemeHint('  example.local  ')).toBe(true);
  });
  it('hides when a scheme is present or the field is empty', () => {
    expect(srvSchemeHint('http://172.16.50.9/')).toBe(false);
    expect(srvSchemeHint('https://example.com/')).toBe(false);
    expect(srvSchemeHint('')).toBe(false);
    expect(srvSchemeHint('   ')).toBe(false);
  });
});
