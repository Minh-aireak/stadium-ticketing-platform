import { useCallback, useEffect, useRef, useState } from 'react'
import { isAxiosError } from 'axios'
import { motion } from 'framer-motion'
import { CheckCircle2, Clock, Loader2, XCircle } from 'lucide-react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getBooking } from '@/features/booking/bookingApi'
import type { BookingStatusResponse } from '@/features/booking/types'
import { CardPaymentForm } from '@/features/payment/CardPaymentForm'
import { getPaymentStatus, syncPayment } from '@/features/payment/paymentApi'
import type { PaymentStatusResponse } from '@/features/payment/types'

/** The happy path resolves in seconds — a Kafka round trip — so start tight. */
const FIRST_POLL_DELAY_MS = 2_500

/**
 * Widen from there. Almost all the information is in the first few seconds; past that, polling
 * four times a minute tells you exactly what polling twenty-four times a minute would.
 */
const POLL_BACKOFF_FACTOR = 1.5
const MAX_POLL_DELAY_MS = 30_000

/**
 * When to stop asking altogether.
 *
 * <p>Not an arbitrary number of attempts: it is what booking-service guarantees. Its
 * BookingReconciliationJob only considers bookings last updated more than
 * booking.payment-reconciliation-job.grace-minutes ago (5), and runs every fixed-delay-ms (5 min),
 * so a booking stuck in PENDING_PAYMENT is resolved to CONFIRMED or CANCELLED within ~10 minutes
 * of the last update, worst case. Past that the server has already decided; a page that keeps
 * polling is asking a question that has an answer, and one that never gives up is a tab quietly
 * making two requests every 2.5s for as long as it stays open.
 */
const GIVE_UP_AFTER_MS = 11 * 60 * 1_000

/**
 * 'pay' is card mode's extra step: payment-service opened an intent and is waiting for the
 * customer to confirm it, so the page shows the card form instead of a spinner. It is still a
 * non-terminal state and keeps polling underneath -- the webhook or the expiry job can end the
 * payment while the form is on screen, and the form must give way when they do.
 */
type ViewState = 'pending' | 'pay' | 'success' | 'failed'

const OPEN_VIEWS: ReadonlySet<ViewState> = new Set<ViewState>(['pending', 'pay'])

/**
 * The booking is the order; the payment is one step inside it. So a cancelled booking is
 * checked first and decides the answer on its own.
 *
 * <p>SUCCEEDED-payment-with-CANCELLED-booking is a real pairing, not a contradiction to be
 * ignored: PAYMENT_SUCCEEDED arriving after the booking was already cancelled makes
 * booking-service request an idempotent refund and leave the booking CANCELLED (see
 * BookingOrchestrationService#confirmBooking). Checking the payment first told that customer
 * "Thanh toán thành công! Vé điện tử của bạn đã sẵn sàng trong tài khoản" about an order that
 * had been cancelled and was being refunded — and the page then stopped polling, because a
 * non-pending view ends the schedule, so the wrong answer was also the final one.
 */
function resolveView(
  booking: BookingStatusResponse | null,
  payment: PaymentStatusResponse | null,
): ViewState {
  if (booking?.status === 'CANCELLED' || payment?.status === 'FAILED') return 'failed'
  if (booking?.status === 'CONFIRMED' || payment?.status === 'SUCCEEDED') return 'success'
  if (payment?.status === 'INITIATED' && payment.clientSecret) return 'pay'
  return 'pending'
}

export function PaymentStatusPage() {
  const { bookingId } = useParams<{ bookingId: string }>()
  const location = useLocation()
  const context = location.state as { matchLabel?: string; seatCodes?: string[] } | null

  const [booking, setBooking] = useState<BookingStatusResponse | null>(null)
  const [payment, setPayment] = useState<PaymentStatusResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [gaveUp, setGaveUp] = useState(false)
  const [rechecking, setRechecking] = useState(false)
  const view = resolveView(booking, payment)
  const viewRef = useRef(view)
  const mountedRef = useRef(true)

  useEffect(() => {
    viewRef.current = view
  }, [view])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const pollOnce = useCallback(async () => {
    if (!bookingId) return
    try {
      const [bookingRes, paymentRes] = await Promise.allSettled([
        getBooking(bookingId),
        getPaymentStatus(bookingId),
      ])
      if (!mountedRef.current) return
      if (bookingRes.status === 'fulfilled') setBooking(bookingRes.value)
      if (paymentRes.status === 'fulfilled') {
        setPayment(paymentRes.value)
        setError(null)
      } else if (!isAxiosError(paymentRes.reason) || paymentRes.reason.response?.status !== 404) {
        // A 404 right after creation just means the payment record hasn't landed yet — keep polling.
        setError('Không thể tải trạng thái thanh toán.')
      }
    } catch {
      if (mountedRef.current) setError('Không thể tải trạng thái đơn hàng.')
    }
  }, [bookingId])

  useEffect(() => {
    if (!bookingId) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const startedAt = Date.now()
    let delay = FIRST_POLL_DELAY_MS

    function scheduleNext() {
      timer = setTimeout(async () => {
        if (cancelled) return
        // A hidden tab is exactly the case that used to poll forever: nobody is watching it, and
        // the confirmation email is what actually reaches the customer either way. Skip the
        // request but keep the schedule, so returning to the tab does not start from cold.
        if (document.visibilityState === 'visible') {
          await pollOnce()
        }
        if (cancelled || !OPEN_VIEWS.has(viewRef.current)) return
        if (Date.now() - startedAt >= GIVE_UP_AFTER_MS) {
          setGaveUp(true)
          return
        }
        delay = Math.min(delay * POLL_BACKOFF_FACTOR, MAX_POLL_DELAY_MS)
        scheduleNext()
      }, delay)
    }

    void pollOnce().then(() => {
      if (!cancelled && OPEN_VIEWS.has(viewRef.current)) scheduleNext()
    })

    // Coming back to the tab re-checks immediately rather than waiting out the current delay —
    // and does so even after giving up, since the answer may have arrived while it was hidden.
    function onVisibilityChange() {
      if (cancelled || document.visibilityState !== 'visible') return
      if (!OPEN_VIEWS.has(viewRef.current)) return
      void pollOnce()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      cancelled = true
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [bookingId, pollOnce])

  const recheck = useCallback(async () => {
    setRechecking(true)
    await pollOnce()
    if (mountedRef.current) setRechecking(false)
  }, [pollOnce])

  // Stripe.js has confirmed the intent in the browser. Ask payment-service to read the outcome
  // from Stripe and record it; the response is the payment as the server now sees it, which is
  // what flips the view -- not the browser's own report.
  const confirmed = useCallback(async () => {
    if (!bookingId) return
    try {
      const synced = await syncPayment(bookingId)
      if (mountedRef.current) setPayment(synced)
    } catch {
      // The webhook or the next poll will still get there; say so rather than fail the page.
      if (mountedRef.current) setError('Đã thanh toán, đang chờ xác nhận từ cổng thanh toán…')
    }
  }, [bookingId])

  if (!bookingId) {
    return <Navigate to="/" replace />
  }

  return (
    <section className="mx-auto max-w-lg px-4 py-16 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <Card>
          <CardContent className="flex flex-col items-center gap-4 p-10 text-center">
            {view === 'pending' && !gaveUp && (
              <>
                <Loader2 className="size-12 animate-spin text-accent" />
                <h1 className="text-xl font-bold">Đang xử lý thanh toán…</h1>
                <p className="text-sm text-muted">
                  Đơn đặt vé <span className="font-mono">{bookingId}</span> đang chờ xác
                  nhận từ cổng thanh toán. Trang này sẽ tự cập nhật.
                </p>
              </>
            )}

            {view === 'pay' && payment?.clientSecret && (
              <>
                <h1 className="text-xl font-bold">Thanh toán</h1>
                {context?.matchLabel && (
                  <p className="text-sm text-muted">
                    {context.matchLabel} — ghế {context.seatCodes?.join(', ')}
                  </p>
                )}
                <CardPaymentForm
                  bookingId={bookingId}
                  clientSecret={payment.clientSecret}
                  expiresAt={payment.expiresAt}
                  lastFailureReason={payment.failureReason}
                  onConfirmed={confirmed}
                />
              </>
            )}

            {view === 'pending' && gaveUp && (
              <>
                <Clock className="size-12 text-warning" />
                <h1 className="text-xl font-bold">Đơn hàng vẫn đang được xử lý</h1>
                <p className="text-sm text-muted">
                  Đơn đặt vé <span className="font-mono">{bookingId}</span> lâu hơn bình
                  thường. Chúng tôi sẽ gửi email cho bạn ngay khi có kết quả — dù thành
                  công hay không, bạn không cần giữ trang này mở.
                </p>
                <div className="flex gap-3">
                  <Button variant="outline" onClick={recheck} disabled={rechecking}>
                    {rechecking ? 'Đang kiểm tra…' : 'Kiểm tra lại'}
                  </Button>
                  <Button asChild variant="gradient">
                    <Link to="/account">Xem đơn của tôi</Link>
                  </Button>
                </div>
              </>
            )}

            {view === 'success' && (
              <>
                <CheckCircle2 className="size-12 text-primary" />
                <h1 className="text-xl font-bold">Thanh toán thành công!</h1>
                {context?.matchLabel && (
                  <p className="text-sm text-muted">
                    {context.matchLabel} — ghế {context.seatCodes?.join(', ')}
                  </p>
                )}
                <p className="text-sm text-muted">
                  Vé điện tử của bạn đã sẵn sàng trong tài khoản.
                </p>
                <div className="flex gap-3">
                  <Button asChild variant="outline">
                    <Link to="/">Về trang chủ</Link>
                  </Button>
                  <Button asChild variant="gradient">
                    <Link to="/account">Xem vé của tôi</Link>
                  </Button>
                </div>
              </>
            )}

            {view === 'failed' && (
              <>
                <XCircle className="size-12 text-danger" />
                <h1 className="text-xl font-bold">Thanh toán không thành công</h1>
                <p className="text-sm text-muted">
                  {payment?.failureReason ?? 'Giao dịch đã bị huỷ hoặc gặp lỗi.'}
                </p>
                <Button asChild variant="gradient">
                  <Link to="/">Thử đặt vé lại</Link>
                </Button>
              </>
            )}

            {error && <p className="text-xs text-danger">{error}</p>}
          </CardContent>
        </Card>
      </motion.div>
    </section>
  )
}
