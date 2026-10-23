import { describe, expect, it } from 'vitest';
import { passwordRules, validateEmail, validateFullName, validateLogin, validateNewPassword, validateRegistration } from './validation';

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
    expect(passwordRules('abc')).toEqual({ length: false, lowercase: true, uppercase: false, digit: false });
    expect(passwordRules('Abcdefg1')).toEqual({ length: true, lowercase: true, uppercase: true, digit: true });
  });
});

describe('validateFullName', () => {
  it('requires a trimmed name up to 255 chars', () => {
    expect(validateFullName('  ')).toBeDefined();
    expect(validateFullName('An Lê')).toBeUndefined();
    expect(validateFullName('a'.repeat(256))).toMatch(/255/);
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
