/**
 * Formats an ISO-8601 date string into a friendly relative time label in Vietnamese.
 * Falls back to concise absolute date when older than a month.
 */
export function formatRelativeTime(
  isoString: string | null | undefined,
  baseDate: Date = new Date()
): string {
  if (!isoString) return '';

  const date = new Date(isoString);
  const time = date.getTime();
  if (Number.isNaN(time)) return '';

  const now = baseDate.getTime();
  const diffSeconds = Math.floor((now - time) / 1000);

  // Negative diff (clock skew between client/server) or less than 45 seconds
  if (diffSeconds < 45) {
    return 'Vừa xong';
  }

  const diffMinutes = Math.floor(diffSeconds / 60);
  if (diffMinutes < 60) {
    return `${diffMinutes} phút trước`;
  }

  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} giờ trước`;
  }

  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) {
    return `${diffDays} ngày trước`;
  }

  const diffWeeks = Math.floor(diffDays / 7);
  if (diffDays < 30) {
    return `${diffWeeks} tuần trước`;
  }

  // Older than ~30 days
  const isSameYear = date.getFullYear() === baseDate.getFullYear();
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');

  if (isSameYear) {
    return `${day}/${month}`;
  }

  return `${day}/${month}/${date.getFullYear()}`;
}
