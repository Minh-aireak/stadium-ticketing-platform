import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Stripe.js must never load in tests: Elements becomes a pass-through, PaymentElement a marker,
// and the two hooks hand back whatever the test put in `stripeStub` / `elementsStub`.
const confirmPayment = vi.fn()
let stripeStub: { confirmPayment: typeof confirmPayment } | null = null
let elementsStub: object | null = null
vi.mock('@stripe/react-stripe-js', () => ({
  Elements: ({ children }: { children: ReactNode }) => <>{children}</>,
  PaymentElement: () => <div data-testid="payment-element" />,
  useStripe: () => stripeStub,
  useElements: () => elementsStub,
}))
vi.mock('@stripe/stripe-js', () => ({
  loadStripe: vi.fn(() => Promise.resolve(null)),
}))

import { CardPaymentForm } from './CardPaymentForm'

function arrange(overrides: Partial<Parameters<typeof CardPaymentForm>[0]> = {}) {
  const onConfirmed = vi.fn(() => Promise.resolve())
  render(
    <CardPaymentForm
      bookingId="b-1"
      clientSecret="pi_1_secret_x"
      expiresAt={new Date(Date.now() + 5 * 60_000).toISOString()}
      lastFailureReason={null}
      onConfirmed={onConfirmed}
      {...overrides}
    />,
  )
  return { onConfirmed }
}

describe('CardPaymentForm', () => {
  beforeEach(() => {
    window.__APP_CONFIG__ = { stripePublishableKey: 'pk_test_x' }
    stripeStub = { confirmPayment }
    elementsStub = {}
    confirmPayment.mockReset()
  })

  afterEach(() => {
    delete window.__APP_CONFIG__
  })

  it('renders the payment element and the countdown for an open intent', () => {
    arrange()
    expect(screen.getByTestId('payment-element')).toBeDefined()
    expect(screen.getByTestId('payment-countdown').textContent).toMatch(/^[45]:[0-5]\d$/)
    expect(screen.getByRole('button', { name: 'Thanh toán' })).toBeDefined()
  })

  // Only Stripe's verdict, read back by payment-service, confirms a booking: the form's job on
  // success is just to tell the page to go and ask.
  it('asks the page to sync after Stripe confirms the intent', async () => {
    confirmPayment.mockResolvedValue({ paymentIntent: { status: 'succeeded' } })
    const { onConfirmed } = arrange()

    fireEvent.click(screen.getByRole('button', { name: 'Thanh toán' }))

    await waitFor(() => expect(onConfirmed).toHaveBeenCalledTimes(1))
    expect(confirmPayment).toHaveBeenCalledWith(
      expect.objectContaining({ redirect: 'if_required' }),
    )
  })

  // A decline leaves the intent open at Stripe and the customer at the form with another card;
  // nothing on the server has moved, so nothing here may navigate away or report success.
  it('shows the decline and stays on the form', async () => {
    confirmPayment.mockResolvedValue({ error: { message: 'Thẻ của bạn đã bị từ chối.' } })
    const { onConfirmed } = arrange()

    fireEvent.click(screen.getByRole('button', { name: 'Thanh toán' }))

    await waitFor(() => expect(screen.getByText('Thẻ của bạn đã bị từ chối.')).toBeDefined())
    expect(onConfirmed).not.toHaveBeenCalled()
    expect(screen.getByTestId('payment-element')).toBeDefined()
  })

  it('shows the reason payment-service kept from an earlier declined attempt', () => {
    arrange({ lastFailureReason: 'Your card was declined.' })
    expect(screen.getByText('Your card was declined.')).toBeDefined()
  })

  // Past the deadline the seats are back on sale; a confirm now would pay for nothing.
  it('disables payment once the window has closed', () => {
    arrange({ expiresAt: new Date(Date.now() - 1_000).toISOString() })
    expect(screen.getByText(/Hết thời gian thanh toán/)).toBeDefined()
    expect(screen.queryByTestId('payment-element')).toBeNull()
    expect((screen.getByRole('button', { name: 'Thanh toán' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('says so instead of rendering an empty form when no publishable key is configured', () => {
    delete window.__APP_CONFIG__
    vi.stubEnv('VITE_STRIPE_PUBLISHABLE_KEY', '')
    arrange()
    expect(screen.getByText(/chưa được cấu hình/)).toBeDefined()
    expect(screen.queryByTestId('payment-element')).toBeNull()
    vi.unstubAllEnvs()
  })
})
