import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'

import { getCorrelationId, getErrorMessage, isDuplicateEmailError, isInvalidResetTokenError } from './errors'

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
})
