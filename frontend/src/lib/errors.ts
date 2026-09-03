import { isAxiosError } from 'axios'

import { CORRELATION_ID_HEADER } from '@/lib/api'

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
    const detail: unknown = error.response?.data?.detail
    const problemDetail = typeof detail === 'string' && detail.length > 0 ? detail : undefined
    // The gateway's 429 is the shape described above and has no `detail` at all, so it still
    // lands on the rate-limit sentence. A 429 that does carry one came through a service's
    // GlobalExceptionHandler instead, and that sentence describes what actually happened —
    // replacing it with "wait a few minutes" would explain something the caller never hit.
    if (error.response?.status === 429 && problemDetail === undefined) {
      return formatRateLimitMessage(error.response)
    }
    if (problemDetail !== undefined) return appendCorrelationId(error, problemDetail)
  }
  return appendCorrelationId(error, fallback)
}

/**
 * The correlation ID this request was logged under, from the response header the gateway always
 * sets, falling back to the ID the request itself carried (present even on a response the browser
 * refuses to expose headers for).
 */
export function getCorrelationId(error: unknown): string | undefined {
  if (!isAxiosError(error)) return undefined
  const fromResponse = error.response?.headers?.['x-correlation-id']
  if (typeof fromResponse === 'string' && fromResponse.length > 0) return fromResponse
  const fromRequest = error.config?.headers?.get?.(CORRELATION_ID_HEADER)
  return typeof fromRequest === 'string' && fromRequest.length > 0 ? fromRequest : undefined
}

// Only on 5xx: the failure is then on the server side and someone has to go and read the logs,
// which the ID turns into a single Kibana filter across every service the request touched. A 4xx
// is a domain answer the user can act on themselves, and a code next to it is just noise. Absent
// entirely on a network error, where the request may never have reached a server that logged it.
function appendCorrelationId(error: unknown, message: string): string {
  const status = isAxiosError(error) ? error.response?.status : undefined
  if (status === undefined || status < 500) return message
  const correlationId = getCorrelationId(error)
  return correlationId ? `${message} (Mã lỗi: ${correlationId})` : message
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

/**
 * True for the 422 raised by the seat hold/reserve endpoints when one or more requested seats are
 * already held or sold (see ticket-inventory-service SeatsNotAvailableException). Its detail is
 * English and names the internal showtimeId, so a caller with a seat-specific sentence of its own
 * has to recognise this case rather than pass that sentence to getErrorMessage as a fallback —
 * the fallback only applies when the response carried no detail at all.
 */
export function isSeatsUnavailableError(error: unknown): boolean {
  if (!isAxiosError(error) || error.response?.status !== 422) return false
  const detail = error.response?.data?.detail
  return typeof detail === 'string' && /seats not available/i.test(detail)
}

/**
 * True for the other 422 the seat hold/reserve endpoints raise: the showtime itself has stopped
 * selling, because its match was cancelled or completed or its kickoff has passed (see
 * ticket-inventory-service ShowtimeBookingClosedException). Its detail is English and names the
 * internal showtimeId, exactly as {@link isSeatsUnavailableError}'s does.
 *
 * <p>Pinned to 422 rather than to the sentence alone, so it cannot swallow the failure it was
 * split away from: a catalog that could not be reached is ShowtimeCatalogUnavailableException,
 * deliberately not a DomainException, and answers 503 with a correlation id the customer is meant
 * to see. Telling them sales had closed would be the very confusion that split fixed.
 */
export function isBookingClosedError(error: unknown): boolean {
  if (!isAxiosError(error) || error.response?.status !== 422) return false
  const detail = error.response?.data?.detail
  return typeof detail === 'string' && /booking is closed/i.test(detail)
}
