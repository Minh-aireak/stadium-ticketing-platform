import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import type { BookingStatus } from '@/features/booking/types'
import type { PaymentStatus } from '@/features/payment/types'
import { PaymentStatusPage } from './PaymentStatusPage'

const getBooking = vi.fn()
const getPaymentStatus = vi.fn()
const syncPayment = vi.fn()

vi.mock('@/features/booking/bookingApi', () => ({
  getBooking: (...args: unknown[]) => getBooking(...args),
}))
vi.mock('@/features/payment/paymentApi', () => ({
  getPaymentStatus: (...args: unknown[]) => getPaymentStatus(...args),
  syncPayment: (...args: unknown[]) => syncPayment(...args),
}))
// The real form loads Stripe.js; here it is a button that plays the part of a confirmed intent.
vi.mock('@/features/payment/CardPaymentForm', () => ({
  CardPaymentForm: ({ onConfirmed }: { onConfirmed: () => Promise<void> }) => (
    <button onClick={() => void onConfirmed()}>stub-pay</button>
  ),
}))

function arrange(bookingStatus: BookingStatus, paymentStatus: PaymentStatus, clientSecret?: string) {
  getBooking.mockResolvedValue({ bookingId: 'b-1', status: bookingStatus })
  getPaymentStatus.mockResolvedValue({
    paymentId: 'p-1',
    bookingId: 'b-1',
    status: paymentStatus,
    gatewayTransactionId: null,
    failureReason: null,
    clientSecret: clientSecret ?? null,
    expiresAt: clientSecret ? new Date(Date.now() + 8 * 60_000).toISOString() : null,
  })

  render(
    <MemoryRouter initialEntries={['/checkout/b-1/status']}>
      <Routes>
        <Route path="/checkout/:bookingId/status" element={<PaymentStatusPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

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

  // Card mode: an INITIATED payment that carries a client secret is waiting on the customer,
  // not on the gateway, so the page must show the form rather than a spinner.
  it('shows the card form for an open card-mode payment', async () => {
    arrange('PENDING_PAYMENT', 'INITIATED', 'pi_1_secret_x')
    await waitFor(() => expect(screen.getByText('stub-pay')).toBeDefined())
    expect(screen.queryByText('Đang xử lý thanh toán…')).toBeNull()
  })

  // What flips the view is payment-service's answer to /sync -- the server reading Stripe --
  // never the form's own report of success.
  it('syncs with payment-service after the form confirms and shows what the server says', async () => {
    arrange('PENDING_PAYMENT', 'INITIATED', 'pi_1_secret_x')
    syncPayment.mockResolvedValue({
      paymentId: 'p-1',
      bookingId: 'b-1',
      status: 'SUCCEEDED',
      gatewayTransactionId: 'pi_1',
      failureReason: null,
    })
    await waitFor(() => expect(screen.getByText('stub-pay')).toBeDefined())

    fireEvent.click(screen.getByText('stub-pay'))

    await waitFor(() => expect(screen.getByText('Thanh toán thành công!')).toBeDefined())
    expect(syncPayment).toHaveBeenCalledWith('b-1')
  })

  // The expiry job ends a card payment the customer never finished; the next poll sees FAILED
  // and the form has to give way to the failure view, not sit there with a dead intent.
  it('replaces the form with the failure view once the payment has expired server-side', async () => {
    arrange('PENDING_PAYMENT', 'INITIATED', 'pi_1_secret_x')
    await waitFor(() => expect(screen.getByText('stub-pay')).toBeDefined())

    getPaymentStatus.mockResolvedValue({
      paymentId: 'p-1',
      bookingId: 'b-1',
      status: 'FAILED',
      gatewayTransactionId: null,
      failureReason: 'Payment window of 8 minutes expired',
      clientSecret: null,
      expiresAt: null,
    })
    getBooking.mockResolvedValue({ bookingId: 'b-1', status: 'CANCELLED' })

    await waitFor(() => expect(screen.getByText('Thanh toán không thành công')).toBeDefined(), {
      timeout: 5_000,
    })
    expect(screen.queryByText('stub-pay')).toBeNull()
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
