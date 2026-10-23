export const DEFAULT_AFTER_LOGIN = '/workspaces';
const LOGIN_PATH = '/login';
const MAX_RETURN_TO_LENGTH = 2048;
// Sentinel origin used only to resolve the candidate; never navigated to.
const PROBE_ORIGIN = 'https://devflow.invalid';

/**
 * Accepts only same-origin, root-relative paths for post-login navigation (open-redirect guard).
 * Rejects protocol-relative ("//host"), backslash variants browsers normalise to "//host", control
 * characters the URL parser strips, absolute URLs, and the login page itself (to avoid a loop).
 */
export function sanitizeReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_RETURN_TO_LENGTH) return null;
  if (!value.startsWith('/') || value.startsWith('//')) return null;
  // eslint-disable-next-line no-control-regex
  if (value.includes('\\') || /[\u0000-\u001f\u007f]/.test(value)) return null;
  let url: URL;
  try {
    url = new URL(value, PROBE_ORIGIN);
  } catch {
    return null;
  }
  // Dot segments can collapse "/..//host" into "//host", so re-check the normalised path.
  if (url.origin !== PROBE_ORIGIN || url.pathname.startsWith('//')) return null;
  if (url.pathname === LOGIN_PATH || url.pathname.startsWith(`${LOGIN_PATH}/`)) return null;
  return `${url.pathname}${url.search}${url.hash}`;
}

/** Login route that sends the user back to `returnTo` once they sign in again. */
export function loginPathWithReturnTo(returnTo: string | null | undefined) {
  const safe = sanitizeReturnTo(returnTo);
  return safe ? `${LOGIN_PATH}?returnTo=${encodeURIComponent(safe)}` : LOGIN_PATH;
}

export function returnToFromSearch(search: string) {
  return sanitizeReturnTo(new URLSearchParams(search).get('returnTo')) ?? DEFAULT_AFTER_LOGIN;
}
