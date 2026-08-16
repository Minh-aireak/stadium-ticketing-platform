import { isAxiosError } from 'axios'

// Backend errors follow RFC 7807 ProblemDetail (see common/GlobalExceptionHandler):
// { type, title, status, detail, timestamp }. `detail` is the human-readable message.
//
// The one exception is the gateway's 429 rate-limit response, which is hand-built and has a
// different shape: { error: 'rate_limit_exceeded', retryAfterSeconds } plus a Retry-After header.
export function getErrorMessage(
  error: unknown,
  fallback = 'Đã có lỗi xảy ra, vui lòng thử lại.',
): string {
  if (isAxiosError(error)) {
    if (error.code === 'ERR_NETWORK') {
      return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại kết nối hoặc thử lại sau.'
    }
    if (error.response?.status === 429) {
      return formatRateLimitMessage(error.response)
    }
    const detail = error.response?.data?.detail
    if (typeof detail === 'string' && detail.length > 0) return detail
  }
  return fallback
}

function formatRateLimitMessage(response: { data?: unknown; headers?: Record<string, unknown> }): string {
  const data = response.data as { retryAfterSeconds?: unknown } | undefined
  const retryAfterSeconds =
    typeof data?.retryAfterSeconds === 'number'
      ? data.retryAfterSeconds
      : Number(response.headers?.['retry-after'])

  if (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
    const wait =
      retryAfterSeconds >= 60
        ? `${Math.ceil(retryAfterSeconds / 60)} phút`
        : `${Math.ceil(retryAfterSeconds)} giây`
    return `Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau ${wait}.`
  }
  return 'Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau ít phút.'
}

/** True for the 422 "email already registered" domain error from POST /auth/register. */
export function isDuplicateEmailError(error: unknown): boolean {
  if (!isAxiosError(error) || error.response?.status !== 422) return false
  const detail = error.response?.data?.detail
  return typeof detail === 'string' && /already registered/i.test(detail)
}

/**
 * True for the 422 raised by POST /auth/reset-password when the one-time token is missing,
 * unknown, or expired (see identity-service InvalidPasswordResetTokenException). Distinguished
 * from a password-policy 422 on the same endpoint, which the user can fix in place — an expired
 * token instead needs a whole new link.
 */
export function isInvalidResetTokenError(error: unknown): boolean {
  if (!isAxiosError(error) || error.response?.status !== 422) return false
  const detail = error.response?.data?.detail
  return typeof detail === 'string' && /reset token/i.test(detail)
}
