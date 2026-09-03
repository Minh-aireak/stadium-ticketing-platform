import axios from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

import { api, registerSessionExpiredHandler, setAccessToken } from '@/lib/api'
import { createBooking, getBooking } from './bookingApi'
import type { CreateBookingRequest } from './types'

const payload: CreateBookingRequest = {
  customerId: 'cust-1',
  showtimeId: 'show-1',
  seatCodes: ['A1'],
  amount: 100000,
  currency: 'VND',
}

let onSessionExpired: Mock<() => void>

beforeEach(() => {
  onSessionExpired = vi.fn<() => void>()
  registerSessionExpiredHandler(onSessionExpired)
  setAccessToken(null)
})

afterEach(() => {
  setAccessToken(null)
})

describe('createBooking', () => {
  /**
   * The regression. createBooking needs a token before it dispatches, so it refreshes inline
   * rather than reacting to a 401 — which meant it also bypassed the response interceptor's
   * session-expired handling. A customer whose session had really expired got the checkout
   * page's own generic "Không thể tạo đơn đặt vé. Vui lòng thử lại." and could retry it
   * forever: AuthProvider was never told, so status stayed 'authenticated' and ProtectedRoute
   * never sent them to log in.
   */
  it('reports an expired session when the pre-flight refresh fails', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh rejected'))

    await expect(createBooking(payload, 'key-1')).rejects.toThrow('refresh rejected')
    expect(onSessionExpired).toHaveBeenCalledOnce()
  })

  it('does not cry session-expired when the refresh succeeds', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { accessToken: 'fresh-token' } })
    // Spied on the instance, not on Axios.prototype: axios.create copies the prototype's
    // methods onto the instance and binds them at creation time, so a prototype spy installed
    // afterwards is never consulted.
    const post = vi.spyOn(api, 'post')
      .mockResolvedValue({ data: { bookingId: 'b-1', status: 'PENDING_PAYMENT' } })

    await expect(createBooking(payload, 'key-1')).resolves.toEqual({
      bookingId: 'b-1',
      status: 'PENDING_PAYMENT',
    })
    expect(onSessionExpired).not.toHaveBeenCalled()
    expect(post.mock.calls[0][2]).toMatchObject({
      headers: { Authorization: 'Bearer fresh-token', 'Idempotency-Key': 'key-1' },
    })
  })

  it('skips the refresh entirely when a token is already in memory', async () => {
    setAccessToken('in-memory-token')
    const refresh = vi.spyOn(axios, 'post')
    vi.spyOn(api, 'post')
      .mockResolvedValue({ data: { bookingId: 'b-2', status: 'PENDING_PAYMENT' } })

    await createBooking(payload, 'key-2')

    expect(refresh).not.toHaveBeenCalled()
    expect(onSessionExpired).not.toHaveBeenCalled()
  })
})

describe('getBooking', () => {
  /**
   * BookingStatusResponse carried only { bookingId, status } under a comment claiming it mirrors
   * booking-service's BookingController DTOs. The record there has carried the server-computed
   * amount and currency since payment-service needed something to check a charge against, and
   * its javadoc calls this response the source of truth for that comparison. Reading either
   * field through the old type was a compile error.
   */
  it('surfaces the server-computed amount the booking was charged', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: { bookingId: 'b-1', status: 'CONFIRMED', amount: 400000, currency: 'VND' },
    })

    const booking = await getBooking('b-1')

    expect(booking.status).toBe('CONFIRMED')
    expect(booking.amount).toBe(400000)
    expect(booking.currency).toBe('VND')
  })
})
