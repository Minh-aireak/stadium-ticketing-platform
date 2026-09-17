import { useEffect, useMemo, useState } from 'react'
import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js'
import { loadStripe, type Stripe } from '@stripe/stripe-js'
import { Clock } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { resolveStripePublishableKey } from '@/lib/runtime-config'

/**
 * Loaded once per page, not per form: loadStripe injects Stripe.js into the document, and a
 * fresh call on every render would queue a new script load each time the countdown ticks.
 * Null when no publishable key is configured -- the form then says so instead of rendering an
 * empty box, which is what happens if Elements is given a rejected promise.
 */
let stripePromise: Promise<Stripe | null> | null = null
function stripeLoader(): Promise<Stripe | null> | null {
  const key = resolveStripePublishableKey()
  if (!key) return null
  if (!stripePromise) stripePromise = loadStripe(key)
  return stripePromise
}

export interface CardPaymentFormProps {
  bookingId: string
  clientSecret: string
  /** ISO-8601 instant after which the seats go back on sale; drives the countdown. */
  expiresAt: string | null | undefined
  /**
   * The gateway's reason for the last declined attempt, if any (payment-service keeps it while
   * the intent stays open). Shown above the form so a customer who reloads still sees why.
   */
  lastFailureReason: string | null | undefined
  /**
   * Called after Stripe.js reports the intent confirmed. The page then asks payment-service to
   * read the outcome from Stripe -- the browser's word is not what confirms a booking.
   */
  onConfirmed: () => Promise<void>
}

/**
 * Card mode's checkout step: the Stripe Payment Element for the intent payment-service opened,
 * with the window it stays open for. Everything that moves money happens between the browser and
 * Stripe; nothing typed here reaches this platform's servers.
 */
export function CardPaymentForm(props: CardPaymentFormProps) {
  const loader = useMemo(stripeLoader, [])
  if (!loader) {
    return (
      <p className="rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-sm text-warning">
        Thanh toán bằng thẻ chưa được cấu hình trên môi trường này (thiếu khoá Stripe). Đơn đặt vé
        sẽ tự huỷ khi hết thời gian giữ ghế.
      </p>
    )
  }
  return (
    <Elements
      stripe={loader}
      options={{
        clientSecret: props.clientSecret,
        locale: 'vi',
        appearance: {
          theme: 'night',
          variables: {
            colorPrimary: '#a3e635',
            colorBackground: '#0d111c',
            colorText: '#eef1f8',
            colorTextSecondary: '#8992a9',
            colorDanger: '#f43f5e',
            borderRadius: '8px',
            fontFamily: 'Inter, system-ui, sans-serif',
          },
          rules: {
            '.Input': { borderColor: '#232a3d' },
          },
        },
      }}
    >
      <CardPaymentFields {...props} />
    </Elements>
  )
}

function CardPaymentFields({ bookingId, expiresAt, lastFailureReason, onConfirmed }: CardPaymentFormProps) {
  const stripe = useStripe()
  const elements = useElements()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(lastFailureReason ?? null)
  const secondsLeft = useCountdown(expiresAt)
  const expired = secondsLeft !== null && secondsLeft <= 0

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!stripe || !elements || submitting || expired) return
    setSubmitting(true)
    setError(null)
    try {
      // redirect: 'if_required' keeps card payments on this page; 3-D Secure opens as a modal.
      // return_url still has to exist for the redirect-based methods Stripe may offer, and the
      // status page is the right place to land: it polls until the booking is terminal.
      const result = await stripe.confirmPayment({
        elements,
        confirmParams: { return_url: `${window.location.origin}/checkout/${bookingId}/status` },
        redirect: 'if_required',
      })
      if (result.error) {
        // A decline. The intent stays open and the customer can try another card; nothing on
        // the server has moved (payment-service ignores payment_failed for an open card intent).
        setError(result.error.message ?? 'Thanh toán không thành công. Vui lòng thử lại.')
        return
      }
      await onConfirmed()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex w-full flex-col gap-4 text-left">
      <div className="flex items-center justify-between rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
        <span className="flex items-center gap-2 text-muted">
          <Clock className="size-4" /> Thời gian giữ ghế
        </span>
        <span
          data-testid="payment-countdown"
          className={expired ? 'font-mono font-semibold text-danger' : 'font-mono font-semibold'}
        >
          {secondsLeft === null ? '—' : formatCountdown(Math.max(secondsLeft, 0))}
        </span>
      </div>

      {expired ? (
        <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
          Hết thời gian thanh toán. Ghế đã được trả lại; trang này sẽ cập nhật khi đơn được huỷ.
        </p>
      ) : (
        <PaymentElement options={{ layout: 'tabs' }} />
      )}

      {error && !expired && (
        <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">{error}</p>
      )}

      <Button type="submit" variant="gradient" size="lg" disabled={!stripe || !elements || submitting || expired}>
        {submitting ? 'Đang xác nhận…' : 'Thanh toán'}
      </Button>

      <p className="text-center text-xs text-muted">
        Thẻ được xử lý trực tiếp bởi Stripe; StadiumGo không lưu số thẻ của bạn.
      </p>
    </form>
  )
}

/** Seconds until `expiresAt`, ticking once a second; null when there is no deadline to show. */
function useCountdown(expiresAt: string | null | undefined): number | null {
  const deadline = useMemo(() => (expiresAt ? Date.parse(expiresAt) : NaN), [expiresAt])
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (Number.isNaN(deadline)) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [deadline])
  if (Number.isNaN(deadline)) return null
  return Math.ceil((deadline - now) / 1000)
}

function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}
