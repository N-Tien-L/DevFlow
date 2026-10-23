import { describe, expect, it } from 'vitest';
import { DEFAULT_AFTER_LOGIN, loginPathWithReturnTo, returnToFromSearch, sanitizeReturnTo } from './returnTo';

describe('sanitizeReturnTo', () => {
  it.each([
    '/boards',
    '/boards/123?tab=activity#task-9',
    '/boards/a%20b',
  ])('accepts internal path %s', (path) => {
    expect(sanitizeReturnTo(path)).toBe(path);
  });

  it.each([
    ['protocol-relative', '//evil.example'],
    ['backslash after slash', '/\\evil.example'],
    ['leading backslashes', '\\\\evil.example'],
    ['embedded backslash', '/boards\\..\\evil'],
    ['absolute URL', 'https://evil.example/boards'],
    ['javascript scheme', 'javascript:alert(1)'],
    ['relative path', 'boards'],
    ['tab smuggling', '/\t/evil.example'],
    ['newline smuggling', '/\n/evil.example'],
    ['dot segments collapsing to //', '/..//evil.example'],
    ['encoded dot segments', '/%2e%2e//evil.example'],
    ['login loop', '/login?returnTo=%2Fboards'],
    ['empty', ''],
  ])('rejects %s', (_label, value) => {
    expect(sanitizeReturnTo(value)).toBeNull();
  });

  it('rejects non-strings and oversized values', () => {
    expect(sanitizeReturnTo(null)).toBeNull();
    expect(sanitizeReturnTo(42)).toBeNull();
    expect(sanitizeReturnTo(`/${'a'.repeat(2048)}`)).toBeNull();
  });
});

describe('loginPathWithReturnTo', () => {
  it('encodes a safe route', () => {
    expect(loginPathWithReturnTo('/boards/1?x=2')).toBe('/login?returnTo=%2Fboards%2F1%3Fx%3D2');
  });

  it('drops unsafe or missing routes', () => {
    expect(loginPathWithReturnTo('//evil.example')).toBe('/login');
    expect(loginPathWithReturnTo(null)).toBe('/login');
  });
});

describe('returnToFromSearch', () => {
  it('round-trips the route built by loginPathWithReturnTo', () => {
    const search = loginPathWithReturnTo('/boards/1?x=2#c').split('?').slice(1).join('?');
    expect(returnToFromSearch(`?${search}`)).toBe('/boards/1?x=2#c');
  });

  it('falls back to the default route for unsafe values', () => {
    expect(returnToFromSearch('?returnTo=%2F%2Fevil.example')).toBe(DEFAULT_AFTER_LOGIN);
    expect(returnToFromSearch('?returnTo=%2F%5Cevil.example')).toBe(DEFAULT_AFTER_LOGIN);
    expect(returnToFromSearch('')).toBe(DEFAULT_AFTER_LOGIN);
  });
});
