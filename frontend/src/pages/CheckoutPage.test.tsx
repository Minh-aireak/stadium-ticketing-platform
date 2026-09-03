import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CheckoutPage } from './CheckoutPage'
import type { CheckoutState } from './CheckoutPage'

vi.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'cust-1', email: 'a@b.test', role: 'USER' } }),
}))
vi.mock('@/features/booking/bookingApi', () => ({ createBooking: vi.fn() }))
vi.mock('@/features/seats/seatsApi', () => ({ unholdSeats: vi.fn() }))

const state: CheckoutState = {
  matchId: 'match-1',
  matchLabel: 'A vs B',
  showtimeId: 'show-1',
  seatCodes: ['A1', 'A2'],
  amount: 400000,
  currency: 'VND',
}

function renderCheckout() {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/checkout', state }]}>
      <CheckoutPage />
    </MemoryRouter>,
  )
}

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
})
