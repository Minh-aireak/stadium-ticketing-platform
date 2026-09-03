import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'

import {
  getCorrelationId,
  getErrorMessage,
  isDuplicateEmailError,
  isBookingClosedError,
  isInvalidResetTokenError,
  isSeatsUnavailableError,
} from './errors'

const GENERIC = 'Đã có lỗi xảy ra, vui lòng thử lại.'

function responseError(
  status: number,
  data: unknown,
  headers: Record<string, string> = {},
): AxiosError {
  const error = new AxiosError('Request failed', 'ERR_BAD_RESPONSE')
  error.config = { headers: new AxiosHeaders() } as never
  error.response = {
    status,
    statusText: '',
    data,
    headers,
    config: error.config,
  } as never
  return error
}

describe('getErrorMessage', () => {
  it('uses the ProblemDetail detail every backend service sends', () => {
    expect(getErrorMessage(responseError(400, { detail: 'Đội nhà không được để trống' })))
      .toBe('Đội nhà không được để trống')
  })

  it('names the connection when axios never reached a server', () => {
    const error = new AxiosError('Network Error', 'ERR_NETWORK')
    expect(getErrorMessage(error)).toBe(
      'Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại kết nối hoặc thử lại sau.',
    )
  })

  it('appends the correlation id on 5xx and not on 4xx', () => {
    expect(getErrorMessage(responseError(503, { detail: 'down' }, { 'x-correlation-id': 'cid-1' })))
      .toBe('down (Mã lỗi: cid-1)')
    expect(getErrorMessage(responseError(409, { detail: 'trùng' }, { 'x-correlation-id': 'cid-1' })))
      .toBe('trùng')
  })

  it('falls back to the fallback text when there is no detail', () => {
    expect(getErrorMessage(responseError(500, {}), 'Không thể tải danh sách trận đấu.'))
      .toBe('Không thể tải danh sách trận đấu.')
  })

  /**
   * The gateway's own 429 (RateLimitingWebFilter / PreAuthRateLimitingWebFilter) is hand-built
   * and carries retryAfterSeconds in the body — the only shape a browser can actually read,
   * since Retry-After is neither CORS-safelisted nor listed in CorsConfig#setExposedHeaders.
   */
  it('turns the gateway 429 body into a wait the user can read', () => {
    expect(getErrorMessage(responseError(429, { error: 'rate_limit_exceeded', retryAfterSeconds: 30 })))
      .toBe('Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau 30 giây.')
    expect(getErrorMessage(responseError(429, { error: 'rate_limit_exceeded', retryAfterSeconds: 90 })))
      .toBe('Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau 2 phút.')
  })

  it('reads Retry-After when the body does not carry the number', () => {
    expect(getErrorMessage(responseError(429, { error: 'rate_limit_exceeded' }, { 'retry-after': '45' })))
      .toBe('Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau 45 giây.')
  })

  it('says "in a few minutes" when neither source has a usable number', () => {
    expect(getErrorMessage(responseError(429, { error: 'rate_limit_exceeded' })))
      .toBe('Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau ít phút.')
  })

  /**
   * A 429 that is a ProblemDetail rather than the gateway's hand-built body has a `detail`
   * written for the user; the canned rate-limit sentence throws it away and tells them to wait
   * for something that is not what happened.
   */
  it('keeps a 429 ProblemDetail detail instead of the canned rate-limit sentence', () => {
    expect(getErrorMessage(responseError(429, { detail: 'Suất này đã hết lượt giữ ghế cho hôm nay' })))
      .toBe('Suất này đã hết lượt giữ ghế cho hôm nay')
  })

  /** A thrown Error is not an AxiosError, so nothing here can read its message. */
  it('cannot surface the message of a plain Error', () => {
    expect(getErrorMessage(new Error('Vui lòng chọn sân vận động'))).toBe(GENERIC)
  })
})

describe('getCorrelationId', () => {
  it('prefers the response header the gateway sets', () => {
    const error = responseError(500, {}, { 'x-correlation-id': 'from-response' })
    error.config = { headers: new AxiosHeaders({ 'X-Correlation-Id': 'from-request' }) } as never
    expect(getCorrelationId(error)).toBe('from-response')
  })

  it('falls back to the id the request carried when the browser hides the header', () => {
    const error = responseError(500, {})
    error.config = { headers: new AxiosHeaders({ 'X-Correlation-Id': 'from-request' }) } as never
    expect(getCorrelationId(error)).toBe('from-request')
  })

  it('is undefined for anything that is not an axios error', () => {
    expect(getCorrelationId(new Error('boom'))).toBeUndefined()
  })
})

describe('domain error predicates', () => {
  it('recognises the duplicate-email 422 and nothing else', () => {
    expect(isDuplicateEmailError(responseError(422, { detail: 'Email already registered' }))).toBe(true)
    expect(isDuplicateEmailError(responseError(422, { detail: 'Password too weak' }))).toBe(false)
    expect(isDuplicateEmailError(responseError(409, { detail: 'Email already registered' }))).toBe(false)
  })

  it('recognises the reset-token 422 and nothing else', () => {
    expect(isInvalidResetTokenError(responseError(422, { detail: 'Password reset token is invalid' }))).toBe(true)
    expect(isInvalidResetTokenError(responseError(422, { detail: 'Password too weak' }))).toBe(false)
  })

  /**
   * ticket-inventory-service answers a seat another shopper already holds with
   * SeatsNotAvailableException — a 422 whose detail is English and names the internal showtimeId.
   * Distinguished from the other 422 the same endpoint raises (ShowtimeBookingClosedException),
   * which is about the whole showtime rather than the one seat just clicked.
   */
  it('recognises the seats-unavailable 422 and nothing else', () => {
    const taken = { detail: 'Seats not available for showtime show-1: A14' }
    expect(isSeatsUnavailableError(responseError(422, taken))).toBe(true)
    expect(isSeatsUnavailableError(responseError(422, { detail: 'Ticket booking is closed for showtime: show-1' })))
      .toBe(false)
    expect(isSeatsUnavailableError(responseError(503, taken))).toBe(false)
    expect(isSeatsUnavailableError(new Error('Seats not available'))).toBe(false)
  })

  /**
   * The other half of the pair above: ShowtimeBookingClosedException, raised when the showtime
   * itself is no longer selling rather than when one seat is taken. Must not swallow the outage
   * beside it — an unreachable catalog is ShowtimeCatalogUnavailableException, deliberately not a
   * DomainException, and answers 503 (see c8ff01a).
   */
  it('recognises the booking-closed 422 and nothing else', () => {
    const closed = { detail: 'Ticket booking is closed for showtime: show-1' }
    expect(isBookingClosedError(responseError(422, closed))).toBe(true)
    expect(isBookingClosedError(responseError(422, { detail: 'Seats not available for showtime show-1: A14' })))
      .toBe(false)
    expect(isBookingClosedError(responseError(503, closed))).toBe(false)
    expect(isBookingClosedError(responseError(422, {}))).toBe(false)
    expect(isBookingClosedError(new Error('Ticket booking is closed'))).toBe(false)
  })
})

/**
 * `type` is a URI constant chosen by whichever handler built the response, and every one of them
 * sets it: common's GlobalExceptionHandler, the two *OverloadExceptionHandlers, the
 * controller-local handlers in match-catalog / ticket-inventory / booking / identity, and the
 * gateway. Where a type identifies one failure whose `detail` is a fixed English sentence, that
 * type — not the sentence — is what the UI can translate.
 *
 * The per-sentence predicates below stay necessary for the ones it cannot: every DomainException
 * on the platform shares the single `domain-error` type, so there the sentence is the only thing
 * that says which rule was broken.
 */
describe('ProblemDetail type mapping', () => {
  function problem(
    status: number,
    typeSuffix: string,
    detail: string,
    headers: Record<string, string> = {},
  ): AxiosError {
    return responseError(
      status,
      { type: `https://aireak.com/errors/${typeSuffix}`, status, detail },
      headers,
    )
  }

  /**
   * LoginService raises InvalidCredentialsException("Invalid credentials") for all four of its
   * refusals and AuthController answers 401 with that as the detail, so this is what a customer
   * who mistypes their password sees — and api.ts skips the 401-refresh retry for any /auth/ URL,
   * so nothing else touches it on the way back.
   */
  it('answers the login 401 in Vietnamese, not with "Invalid credentials"', () => {
    const message = getErrorMessage(
      problem(401, 'invalid-credentials', 'Invalid credentials'),
      'Không thể đăng nhập. Vui lòng thử lại.',
    )
    expect(message).toBe('Email hoặc mật khẩu không đúng.')
  })

  // Every detail here is a string literal in the handler that sends it — checked against the
  // source, not guessed — which is what makes the type enough to translate by.
  it.each<[number, string, string, string]>([
    [503, 'session-store-unavailable', 'Authentication service temporarily unavailable',
      'Hệ thống đăng nhập đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút.'],
    [503, 'reset-token-store-unavailable', 'Password reset is temporarily unavailable',
      'Chức năng đặt lại mật khẩu đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút.'],
    [503, 'verification-token-store-unavailable', 'Email verification is temporarily unavailable',
      'Chưa gửi được email xác minh lúc này. Vui lòng thử lại sau ít phút.'],
    [503, 'search-unavailable', 'Match search is temporarily unavailable',
      'Không tìm kiếm được trận đấu lúc này. Vui lòng thử lại sau ít phút.'],
    [503, 'catalog-unavailable', 'Ticket availability cannot be verified right now',
      'Chưa kiểm tra được tình trạng vé lúc này. Vui lòng thử lại sau ít phút.'],
    [503, 'service-unavailable', 'A service this booking needs is temporarily unavailable',
      'Một dịch vụ cần cho thao tác này đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút.'],
    [503, 'bulkhead-full', 'Match catalog service is at capacity',
      'Hệ thống đang quá tải. Vui lòng thử lại sau giây lát.'],
    [503, 'rate-limited', 'Too many catalog browse requests',
      'Hệ thống đang quá tải. Vui lòng thử lại sau giây lát.'],
    [504, 'gateway-timeout', 'The downstream service took too long to respond',
      'Máy chủ phản hồi quá chậm. Vui lòng thử lại sau ít phút.'],
    [409, 'data-conflict', 'The request conflicts with existing data',
      'Dữ liệu vừa gửi trùng với dữ liệu đã có. Vui lòng tải lại trang rồi thử lại.'],
    // The one detail that is not a constant — BookingOrchestrationService builds it from the
    // Idempotency-Key. Keyed by type all the same, and that is what stops the key being shown.
    [409, 'duplicate-request-in-progress',
      "Request with Idempotency-Key 'e1f2a3b4-0000-4000-8000-000000000000' is already being processed",
      'Đơn đặt vé này đang được xử lý. Vui lòng đợi giây lát rồi kiểm tra lại.'],
  ])('translates the %i %s response', (status, typeSuffix, detail, vietnamese) => {
    expect(getErrorMessage(problem(status, typeSuffix, detail))).toBe(vietnamese)
  })

  it('still appends the correlation id to a translated 5xx', () => {
    const error = problem(503, 'search-unavailable', 'Match search is temporarily unavailable', {
      'x-correlation-id': 'corr-42',
    })
    expect(getErrorMessage(error)).toBe(
      'Không tìm kiếm được trận đấu lúc này. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-42)',
    )
  })

  /**
   * handleGenericException's "An unexpected error occurred" is the one constant that says nothing
   * the caller does not already know, and every call site passes a sentence naming the thing that
   * failed. So this type yields to that sentence rather than to a table entry of its own.
   */
  it('lets the call site answer a bare internal error', () => {
    expect(
      getErrorMessage(
        problem(500, 'internal-error', 'An unexpected error occurred'),
        'Không thể tải danh sách trận đấu.',
      ),
    ).toBe('Không thể tải danh sách trận đấu.')
    expect(getErrorMessage(problem(500, 'internal-error', 'An unexpected error occurred')))
      .toBe(GENERIC)
  })

  /** Guard: the one type the table must never claim, and why the predicates above still exist. */
  it('leaves the shared domain-error type to the per-sentence predicates', () => {
    const closed = problem(422, 'domain-error', 'Ticket booking is closed for showtime: show-1')
    expect(getErrorMessage(closed)).toBe('Ticket booking is closed for showtime: show-1')
    expect(isBookingClosedError(closed)).toBe(true)

    const duplicate = problem(422, 'domain-error', 'Email already registered: a@b.co')
    expect(isDuplicateEmailError(duplicate)).toBe(true)
  })

  /** Guard: a type carrying a request-specific detail keeps that detail. */
  it('renders an untranslated detail verbatim', () => {
    expect(getErrorMessage(problem(400, 'validation-error', 'homeTeam: must not be blank')))
      .toBe('homeTeam: must not be blank')
    expect(getErrorMessage(problem(404, 'not-found', 'Match not found: match-9')))
      .toBe('Match not found: match-9')
  })

  /** Guard: the gateway's hand-built 429 has no `type` at all and must keep its own sentence. */
  it('is not disturbed by the gateway 429', () => {
    expect(getErrorMessage(responseError(429, { error: 'rate_limit_exceeded', retryAfterSeconds: 30 })))
      .toBe('Bạn đã thao tác quá nhiều lần. Vui lòng thử lại sau 30 giây.')
  })

  it('ignores a type outside the platform namespace', () => {
    expect(getErrorMessage(responseError(503, { type: 'about:blank', detail: 'nothing to map' })))
      .toBe('nothing to map')
  })

  /** The suffix is read off the wire, so a name that only exists on Object.prototype is not one. */
  it('does not treat an inherited property name as a translation', () => {
    expect(getErrorMessage(problem(503, 'toString', 'nothing to map'))).toBe('nothing to map')
    expect(getErrorMessage(problem(503, 'constructor', 'nothing to map'))).toBe('nothing to map')
  })
})
