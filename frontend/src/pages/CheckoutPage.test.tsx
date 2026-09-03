import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { CheckoutPage } from './CheckoutPage'
import type { CheckoutState } from './CheckoutPage'

const createBooking = vi.fn()
const unholdSeats = vi.fn()

vi.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'cust-1', email: 'a@b.test', role: 'USER' } }),
}))
vi.mock('@/features/booking/bookingApi', () => ({
  createBooking: (...args: unknown[]) => createBooking(...args),
}))
vi.mock('@/features/seats/seatsApi', () => ({
  unholdSeats: (...args: unknown[]) => unholdSeats(...args),
}))

const state: CheckoutState = {
  matchId: 'match-1',
  matchLabel: 'A vs B',
  showtimeId: 'show-1',
  seatCodes: ['A1', 'A2'],
  amount: 400000,
  currency: 'VND',
}

function renderCheckout(overrides: Partial<CheckoutState> = {}) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/checkout', state: { ...state, ...overrides } }]}>
      <Routes>
        <Route path="/checkout" element={<CheckoutPage />} />
        <Route path="/checkout/:bookingId/status" element={<div>trang trạng thái</div>} />
        <Route path="*" element={<div>đã rời trang</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  createBooking.mockResolvedValue({ bookingId: 'b-1', status: 'PENDING_PAYMENT' })
  unholdSeats.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CheckoutPage', () => {
  it('renders the order it was handed', () => {
    renderCheckout()
    expect(screen.getByText('A vs B')).toBeDefined()
    expect(screen.getByText(/A1, A2/)).toBeDefined()
  })

  /**
   * The regression: the page minted its Idempotency-Key with a bare crypto.randomUUID() during
   * render. That identifier is [SecureContext], so on the SPA served over plain http to a LAN IP
   * — which frontend/nginx.conf's `listen 80` / `server_name _` produces — the call is
   * `undefined()` and the whole checkout page throws instead of rendering.
   */
  it('renders in an insecure context, where crypto.randomUUID is undefined', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: <T extends ArrayBufferView>(array: T): T => {
        const bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength)
        for (let i = 0; i < bytes.length; i += 1) bytes[i] = i * 11 + 5
        return array
      },
    })

    expect(() => renderCheckout()).not.toThrow()
    expect(screen.getByRole('button', { name: /Xác nhận & Thanh toán/ })).toBeDefined()
  })

  /**
   * The total on this screen is the number the customer agrees to be charged. It was rendered by
   * a formatter hard-pinned to VND, so a showtime priced in any other currency — which
   * match-catalog-service accepts and payment-service charges in — was shown with the đồng
   * symbol. The currency was in this page's own navigation state the whole time, destructured
   * out of it and then never used.
   */
  it('shows the total in the currency the order is priced in', () => {
    renderCheckout({ amount: 12.99, currency: 'USD' })
    expect(screen.getByText(/US\$/)).toBeDefined()
    expect(screen.queryByText(/₫/)).toBeNull()
  })

  /**
   * The Idempotency-Key is deliberately stable for the life of this page, so a retried click
   * after a network error lands on the same booking rather than double-booking. But when the
   * saga fails, booking-service cancels the booking and releases only the Redis claim — the row
   * keyed by this Idempotency-Key stays. The next POST therefore falls through to
   * BookingOrchestrationService's findByIdempotencyKey fallback, which does not filter by
   * status, and is answered 201 with the already-CANCELLED booking. The page navigated to the
   * payment status screen regardless, where resolveView reads CANCELLED as 'failed' and tells
   * the customer "Thanh toán không thành công" about a payment nobody ever attempted — while
   * the retry button they had just pressed had, in fact, done nothing.
   */
  it('does not send the customer to the status screen for an already-cancelled booking', async () => {
    createBooking.mockResolvedValue({ bookingId: 'b-1', status: 'CANCELLED' })

    renderCheckout()
    fireEvent.click(screen.getByRole('button', { name: /Xác nhận & Thanh toán/ }))

    await waitFor(() => expect(screen.getByText(/đã bị huỷ/i)).toBeDefined())
    expect(screen.queryByText('trang trạng thái')).toBeNull()
    expect(screen.getByRole('button', { name: /Quay lại chọn ghế khác/ })).toBeDefined()
  })

  it('still goes to the status screen for a booking that is genuinely pending payment', async () => {
    renderCheckout()
    fireEvent.click(screen.getByRole('button', { name: /Xác nhận & Thanh toán/ }))

    await waitFor(() => expect(screen.getByText('trang trạng thái')).toBeDefined())
  })
})
