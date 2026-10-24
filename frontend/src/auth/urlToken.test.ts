// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { removeTokenFromAddressBar, tokenFromSearch } from './urlToken';

afterEach(() => window.history.replaceState(null, '', '/'));

describe('tokenFromSearch', () => {
  it('reads and trims the token', () => {
    expect(tokenFromSearch('?token=%20abc%20')).toBe('abc');
    expect(tokenFromSearch('?a=1&token=xyz')).toBe('xyz');
  });

  it('rejects missing, blank and oversized tokens', () => {
    expect(tokenFromSearch('')).toBeNull();
    expect(tokenFromSearch('?token=')).toBeNull();
    expect(tokenFromSearch('?token=%20%20')).toBeNull();
    expect(tokenFromSearch(`?token=${'x'.repeat(128)}`)).toHaveLength(128);
    expect(tokenFromSearch(`?token=${'x'.repeat(129)}`)).toBeNull();
  });
});

describe('removeTokenFromAddressBar', () => {
  it('replaces the current entry without the token', () => {
    window.history.pushState(null, '', '/reset-password?token=secret');
    const entries = window.history.length;
    expect(removeTokenFromAddressBar()).toBe(true);
    expect(window.location.pathname).toBe('/reset-password');
    expect(window.location.search).toBe('');
    expect(window.location.href).not.toContain('secret');
    // Replaced, not pushed: Back must not lead to the tokenised URL.
    expect(window.history.length).toBe(entries);
  });

  it('keeps other parameters, the hash and history.state', () => {
    window.history.pushState({ idx: 3 }, '', '/reset-password?utm=mail&token=secret#top');
    removeTokenFromAddressBar();
    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe('/reset-password?utm=mail#top');
    expect(window.history.state).toEqual({ idx: 3 });
  });

  it('leaves a URL without a token alone', () => {
    window.history.pushState(null, '', '/reset-password?utm=mail');
    expect(removeTokenFromAddressBar()).toBe(false);
    expect(window.location.search).toBe('?utm=mail');
  });
});
