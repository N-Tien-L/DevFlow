// Client-side checks mirroring the server's Bean Validation on RegisterRequest / LoginRequest /
// ForgotPasswordRequest / ResetPasswordRequest. They only improve feedback; the server re-validates everything.

export const EMAIL_MAX_LENGTH = 254;
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 72;
/** BCrypt reads at most 72 bytes; the server checks the UTF-8 length on top of the character count. */
export const PASSWORD_MAX_BYTES = 72;
export const FULL_NAME_MAX_LENGTH = 255;
export const WORKSPACE_NAME_MAX_LENGTH = 100;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const utf8 = new TextEncoder();

export type RegisterField = 'fullName' | 'email' | 'password';
export type FieldErrors<F extends string> = Partial<Record<F, string>>;

export interface PasswordRules {
  length: boolean;
  /** Accented letters take 2-3 bytes, so a password can fit 72 characters and still exceed 72 bytes. */
  withinByteLimit: boolean;
  lowercase: boolean;
  uppercase: boolean;
  digit: boolean;
}

export function utf8ByteLength(value: string) {
  return utf8.encode(value).length;
}

export function passwordRules(password: string): PasswordRules {
  return {
    length: password.length >= PASSWORD_MIN_LENGTH && password.length <= PASSWORD_MAX_LENGTH,
    withinByteLimit: utf8ByteLength(password) <= PASSWORD_MAX_BYTES,
    lowercase: /[a-z]/.test(password),
    uppercase: /[A-Z]/.test(password),
    digit: /[0-9]/.test(password),
  };
}

export function validateEmail(value: string): string | undefined {
  const email = value.trim();
  if (!email) return 'Vui lòng nhập email.';
  if (email.length > EMAIL_MAX_LENGTH) return `Email không được dài quá ${EMAIL_MAX_LENGTH} ký tự.`;
  if (!EMAIL_PATTERN.test(email)) return 'Email không đúng định dạng.';
  return undefined;
}

export function validateNewPassword(password: string): string | undefined {
  if (!password) return 'Vui lòng nhập mật khẩu.';
  if (password.length < PASSWORD_MIN_LENGTH) return `Mật khẩu cần ít nhất ${PASSWORD_MIN_LENGTH} ký tự.`;
  if (password.length > PASSWORD_MAX_LENGTH) return `Mật khẩu không được dài quá ${PASSWORD_MAX_LENGTH} ký tự.`;
  if (utf8ByteLength(password) > PASSWORD_MAX_BYTES) {
    return `Mật khẩu vượt quá ${PASSWORD_MAX_BYTES} byte (mỗi chữ có dấu chiếm 2–3 byte). Hãy rút ngắn mật khẩu.`;
  }
  const rules = passwordRules(password);
  if (!rules.lowercase || !rules.uppercase || !rules.digit) return 'Mật khẩu cần có chữ hoa, chữ thường và chữ số.';
  return undefined;
}

export function validatePasswordConfirmation(password: string, confirmation: string): string | undefined {
  if (!confirmation) return 'Vui lòng nhập lại mật khẩu mới.';
  if (confirmation !== password) return 'Mật khẩu nhập lại không khớp.';
  return undefined;
}

export function validateFullName(value: string): string | undefined {
  const name = value.trim();
  if (!name) return 'Vui lòng nhập họ và tên.';
  if (name.length > FULL_NAME_MAX_LENGTH) return `Họ và tên không được dài quá ${FULL_NAME_MAX_LENGTH} ký tự.`;
  return undefined;
}

export function validateWorkspaceName(value: string): string | undefined {
  const name = value.trim();
  if (!name) return 'Vui lòng nhập tên workspace.';
  if (name.length > WORKSPACE_NAME_MAX_LENGTH) return `Tên workspace không được dài quá ${WORKSPACE_NAME_MAX_LENGTH} ký tự.`;
  return undefined;
}

export function validateRegistration(values: Record<RegisterField, string>): FieldErrors<RegisterField> {
  const errors: FieldErrors<RegisterField> = {};
  const fullName = validateFullName(values.fullName);
  const email = validateEmail(values.email);
  const password = validateNewPassword(values.password);
  if (fullName) errors.fullName = fullName;
  if (email) errors.email = email;
  if (password) errors.password = password;
  return errors;
}

export function validateLogin(values: { email: string; password: string }): FieldErrors<'email' | 'password'> {
  const errors: FieldErrors<'email' | 'password'> = {};
  const email = validateEmail(values.email);
  if (email) errors.email = email;
  if (!values.password) errors.password = 'Vui lòng nhập mật khẩu.';
  return errors;
}

export type ResetPasswordField = 'newPassword' | 'confirmPassword';

export function validateResetPassword(values: Record<ResetPasswordField, string>): FieldErrors<ResetPasswordField> {
  const errors: FieldErrors<ResetPasswordField> = {};
  const newPassword = validateNewPassword(values.newPassword);
  const confirmPassword = validatePasswordConfirmation(values.newPassword, values.confirmPassword);
  if (newPassword) errors.newPassword = newPassword;
  if (confirmPassword) errors.confirmPassword = confirmPassword;
  return errors;
}

export function validateForgotPassword(values: { email: string }): FieldErrors<'email'> {
  const email = validateEmail(values.email);
  return email ? { email } : {};
}
