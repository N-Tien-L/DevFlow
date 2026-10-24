// One-time tokens (email verification, invitations, password reset) arrive as `?token=` in an emailed link.
// Pages read the value once into memory and strip it from the address bar so it is not bookmarked,
// synced to browser history, shared by copy-paste or leaked through Referer.

const TOKEN_PARAM = 'token';
// Every backend token request DTO caps the value at 128 characters.
export const URL_TOKEN_MAX_LENGTH = 128;

/** The trimmed `?token=` value, or null when it is absent, blank or longer than the server accepts. */
export function tokenFromSearch(search: string): string | null {
  const token = new URLSearchParams(search).get(TOKEN_PARAM)?.trim();
  return token && token.length <= URL_TOKEN_MAX_LENGTH ? token : null;
}

/**
 * Replaces the current history entry with the same URL minus `?token=`, keeping any other query
 * parameters and the hash. Returns whether there was a token to remove.
 */
export function removeTokenFromAddressBar(): boolean {
  const url = new URL(window.location.href);
  if (!url.searchParams.has(TOKEN_PARAM)) return false;
  url.searchParams.delete(TOKEN_PARAM);
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  return true;
}
