// Values handed between auth pages through router state, never through storage or the URL.

/** Notices a page can ask /login to show on arrival. */
export type LoginNotice = 'passwordReset';

function field(state: unknown, key: string): unknown {
  return typeof state === 'object' && state !== null && key in state ? (state as Record<string, unknown>)[key] : undefined;
}

/** Email to prefill (register 409, "check your inbox", login → forgot password). */
export function emailFromState(state: unknown): string {
  const email = field(state, 'email');
  return typeof email === 'string' ? email : '';
}

export function loginNoticeFromState(state: unknown): LoginNotice | null {
  return field(state, 'notice') === 'passwordReset' ? 'passwordReset' : null;
}
