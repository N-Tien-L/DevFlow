import { AuthErrorCode } from '../api/problemDetail';
import { ApiError } from '../types/board';

const FALLBACK = 'Đã có lỗi xảy ra. Vui lòng thử lại.';

/** User-facing Vietnamese copy for auth failures; the server's English `detail` is never shown for known codes. */
export function authErrorMessage(reason: unknown, fallback = FALLBACK): string {
  if (!(reason instanceof ApiError)) return fallback;
  const problem = reason.problem;
  switch (problem?.code) {
    case AuthErrorCode.EmailNotVerified:
      return 'Email chưa được xác minh. Hãy mở liên kết trong hộp thư hoặc gửi lại email xác minh.';
    case AuthErrorCode.BotChallengeFailed:
      return 'Không xác minh được bạn là người thật. Vui lòng thực hiện lại bước kiểm tra bảo mật.';
    case AuthErrorCode.BotProtectionUnavailable:
      return 'Dịch vụ kiểm tra bảo mật đang tạm thời không khả dụng. Vui lòng thử lại sau ít phút.';
    case AuthErrorCode.DisposableEmail:
      return 'Không chấp nhận địa chỉ email dùng một lần. Hãy dùng email cá nhân hoặc công việc.';
    case AuthErrorCode.EmailDomainUndeliverable:
      return 'Tên miền email này không nhận được thư. Hãy kiểm tra lại địa chỉ email.';
    case AuthErrorCode.VerificationTokenInvalid:
      return 'Liên kết xác minh không hợp lệ hoặc đã được sử dụng.';
    case AuthErrorCode.VerificationTokenExpired:
      return 'Liên kết xác minh đã hết hạn. Hãy gửi lại email xác minh.';
    case AuthErrorCode.RateLimitExceeded: {
      const seconds = problem.retryAfterSeconds;
      return seconds ? `Bạn thử quá nhiều lần. Vui lòng đợi ${seconds} giây rồi thử lại.` : 'Bạn thử quá nhiều lần. Vui lòng đợi một lát rồi thử lại.';
    }
    case AuthErrorCode.AuthenticationRequired:
      return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  }
  if (reason.status === 0) return reason.message;
  if (reason.status === 409) return 'Email này đã được đăng ký. Hãy đăng nhập thay vì tạo tài khoản mới.';
  if (reason.status === 400 && problem?.errors) return 'Vui lòng kiểm tra lại các trường được đánh dấu.';
  if (reason.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
  return fallback;
}
