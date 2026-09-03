import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { BookingStatus } from '@/features/booking/types'
import type { PaymentStatus } from '@/features/payment/types'
import { PaymentStatusPage } from './PaymentStatusPage'

const getBooking = vi.fn()
const getPaymentStatus = vi.fn()

vi.mock('@/features/booking/bookingApi', () => ({
  getBooking: (...args: unknown[]) => getBooking(...args),
}))
vi.mock('@/features/payment/paymentApi', () => ({
  getPaymentStatus: (...args: unknown[]) => getPaymentStatus(...args),
}))

function arrange(bookingStatus: BookingStatus, paymentStatus: PaymentStatus) {
  getBooking.mockResolvedValue({ bookingId: 'b-1', status: bookingStatus })
  getPaymentStatus.mockResolvedValue({
    paymentId: 'p-1',
    bookingId: 'b-1',
    status: paymentStatus,
    gatewayTransactionId: null,
    failureReason: null,
  })

  render(
    <MemoryRouter initialEntries={['/checkout/b-1/status']}>
      <Routes>
        <Route path="/checkout/:bookingId/status" element={<PaymentStatusPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.clearAllTimers()
})

describe('PaymentStatusPage', () => {
  it('reports success for a confirmed booking', async () => {
    arrange('CONFIRMED', 'SUCCEEDED')
    await waitFor(() => expect(screen.getByText('Thanh toán thành công!')).toBeDefined())
  })

  it('reports failure for a failed payment', async () => {
    arrange('PENDING_PAYMENT', 'FAILED')
    await waitFor(() => expect(screen.getByText('Thanh toán không thành công')).toBeDefined())
  })

  it('keeps waiting while neither side has resolved', async () => {
    arrange('PENDING_PAYMENT', 'INITIATED')
    await waitFor(() => expect(screen.getByText('Đang xử lý thanh toán…')).toBeDefined())
  })

  /**
   * The regression. booking-service answers a PAYMENT_SUCCEEDED that arrives after the booking
   * was already cancelled by requesting an idempotent refund and leaving the booking CANCELLED,
   * so this pairing is a state the system really produces. Checking the payment first announced
   * "Thanh toán thành công!" — and, since a non-pending view ends the poll schedule, left that
   * as the page's final answer for an order that was being refunded.
   */
  it('reports failure when the booking was cancelled even though the payment succeeded', async () => {
    arrange('CANCELLED', 'SUCCEEDED')

    await waitFor(() => expect(screen.getByText('Thanh toán không thành công')).toBeDefined())
    expect(screen.queryByText('Thanh toán thành công!')).toBeNull()
  })
})
