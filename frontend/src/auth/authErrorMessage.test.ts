import { describe, expect, it } from 'vitest';
import { ApiError } from '../types/board';
import { authErrorMessage } from './authErrorMessage';

describe('authErrorMessage', () => {
  it('uses Vietnamese copy for known codes instead of the server detail', () => {
    const error = new ApiError('Email address has not been verified', 403, { status: 403, code: 'EMAIL_NOT_VERIFIED' });
    expect(authErrorMessage(error)).toMatch(/^Email chưa được xác minh/);
  });

  it('includes the wait time for rate limiting', () => {
    const error = new ApiError('Too many', 429, { status: 429, code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 17 });
    expect(authErrorMessage(error)).toContain('17 giây');
  });

  it('has copy for the password reset token codes', () => {
    expect(authErrorMessage(new ApiError('x', 400, { status: 400, code: 'RESET_TOKEN_INVALID' }))).toMatch(/không hợp lệ/);
    expect(authErrorMessage(new ApiError('x', 410, { status: 410, code: 'RESET_TOKEN_EXPIRED' }))).toMatch(/hết hạn/);
  });

  it('covers 409, network and unknown failures', () => {
    expect(authErrorMessage(new ApiError('exists', 409, { status: 409 }))).toMatch(/đã được đăng ký/);
    expect(authErrorMessage(new ApiError('Không kết nối được máy chủ.', 0))).toBe('Không kết nối được máy chủ.');
    expect(authErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
  });
});
