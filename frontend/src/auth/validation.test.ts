import { describe, expect, it } from 'vitest';
import {
  passwordRules,
  utf8ByteLength,
  validateEmail,
  validateForgotPassword,
  validateFullName,
  validateLogin,
  validateNewPassword,
  validatePasswordConfirmation,
  validateRegistration,
  validateResetPassword,
  validateWorkspaceName,
} from './validation';

describe('validateEmail', () => {
  it('accepts a normal address with surrounding spaces', () => {
    expect(validateEmail('  an@devflow.dev ')).toBeUndefined();
  });

  it('rejects empty, malformed and over-long addresses', () => {
    expect(validateEmail(' ')).toBeDefined();
    expect(validateEmail('an@devflow')).toBeDefined();
    expect(validateEmail('an devflow@x.dev')).toBeDefined();
    expect(validateEmail(`${'a'.repeat(245)}@devflow.dev`)).toMatch(/254/);
    expect(validateEmail(`${'a'.repeat(241)}@devflow.dev`)).toBeUndefined();
  });
});

describe('validateNewPassword', () => {
  it('matches the server rules: 8-72 chars with lower, upper and digit', () => {
    expect(validateNewPassword('Secret12')).toBeUndefined();
    expect(validateNewPassword(`Aa1${'x'.repeat(69)}`)).toBeUndefined();
    expect(validateNewPassword('Secr12')).toMatch(/8/);
    expect(validateNewPassword(`Aa1${'x'.repeat(70)}`)).toMatch(/72/);
    expect(validateNewPassword('secret123')).toBeDefined();
    expect(validateNewPassword('SECRET123')).toBeDefined();
    expect(validateNewPassword('SecretPass')).toBeDefined();
  });

  it('reports each rule separately for the checklist', () => {
    expect(passwordRules('abc')).toEqual({ length: false, withinByteLimit: true, lowercase: true, uppercase: false, digit: false });
    expect(passwordRules('Abcdefg1')).toEqual({ length: true, withinByteLimit: true, lowercase: true, uppercase: true, digit: true });
  });

  it('counts UTF-8 bytes like the server (BCrypt reads 72 bytes)', () => {
    expect(utf8ByteLength('a')).toBe(1);
    expect(utf8ByteLength('é')).toBe(2);
    expect(utf8ByteLength('ệ')).toBe(3);
    expect(utf8ByteLength('😀')).toBe(4);
    // 24 characters, 72 bytes: exactly at the limit.
    expect(validateNewPassword(`Aa1${'ệ'.repeat(23)}`)).toBeUndefined();
    // 25 characters, well under 72, but 75 bytes.
    const tooManyBytes = `Aa1${'ệ'.repeat(24)}`;
    expect(tooManyBytes.length).toBeLessThan(72);
    expect(validateNewPassword(tooManyBytes)).toMatch(/72 byte/);
    expect(passwordRules(tooManyBytes)).toMatchObject({ length: true, withinByteLimit: false });
  });

  it('counts characters as UTF-16 units, as Java @Size does', () => {
    // An emoji is 2 UTF-16 units in both Java and JavaScript: 3 + 35 * 2 = 73 "characters".
    expect(validateNewPassword(`Aa1${'😀'.repeat(35)}`)).toMatch(/72 ký tự/);
  });
});

describe('password reset forms', () => {
  it('requires the confirmation to match exactly', () => {
    expect(validatePasswordConfirmation('Secret12', '')).toBeDefined();
    expect(validatePasswordConfirmation('Secret12', 'Secret13')).toMatch(/không khớp/);
    expect(validatePasswordConfirmation('Secret12', 'Secret12 ')).toMatch(/không khớp/);
    expect(validatePasswordConfirmation('Secret12', 'Secret12')).toBeUndefined();
  });

  it('collects reset and forgot-password errors', () => {
    expect(Object.keys(validateResetPassword({ newPassword: 'weak', confirmPassword: '' }))).toEqual(['newPassword', 'confirmPassword']);
    expect(validateResetPassword({ newPassword: 'Secret123', confirmPassword: 'Secret123' })).toEqual({});
    expect(validateForgotPassword({ email: 'nope' })).toHaveProperty('email');
    expect(validateForgotPassword({ email: ' an@devflow.dev ' })).toEqual({});
  });
});

describe('validateFullName', () => {
  it('requires a trimmed name up to 255 chars', () => {
    expect(validateFullName('  ')).toBeDefined();
    expect(validateFullName('An Lê')).toBeUndefined();
    expect(validateFullName('a'.repeat(256))).toMatch(/255/);
  });
});

describe('validateWorkspaceName', () => {
  it('requires 1-100 characters after trimming', () => {
    expect(validateWorkspaceName('   ')).toBeDefined();
    expect(validateWorkspaceName('  DevFlow Team ')).toBeUndefined();
    expect(validateWorkspaceName(` ${'a'.repeat(100)} `)).toBeUndefined();
    expect(validateWorkspaceName('a'.repeat(101))).toMatch(/100/);
  });
});

describe('form validators', () => {
  it('collects every registration error at once', () => {
    expect(Object.keys(validateRegistration({ fullName: '', email: 'x', password: 'short' }))).toEqual(['fullName', 'email', 'password']);
    expect(validateRegistration({ fullName: 'An', email: 'an@devflow.dev', password: 'Secret123' })).toEqual({});
  });

  it('does not apply new-password rules to login', () => {
    expect(validateLogin({ email: 'an@devflow.dev', password: 'x' })).toEqual({});
    expect(validateLogin({ email: '', password: '' })).toHaveProperty('password');
  });
});
