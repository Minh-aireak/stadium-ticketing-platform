import { useState } from 'react'
import { motion } from 'framer-motion'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { useAuth } from '@/features/auth/AuthContext'
import { createBooking } from '@/features/booking/bookingApi'
import { unholdSeats } from '@/features/seats/seatsApi'
import { formatCurrency } from '@/lib/format'
import { getErrorMessage } from '@/lib/errors'
import { randomUuid } from '@/lib/uuid'

export interface CheckoutState {
  matchId: string
  matchLabel: string
  showtimeId: string
  seatCodes: string[]
  amount: number
  currency: string
}

export function CheckoutPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user } = useAuth()
  const state = location.state as CheckoutState | null

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Stable for the lifetime of this order attempt — a retried click after a network
  // error must reuse the same key so booking-service dedupes it instead of double-booking.
  // A lazy useState initializer rather than useRef(randomUuid()): the useRef form evaluated
  // its argument on every render and discarded all but the first result.
  const [idempotencyKey] = useState(() => randomUuid())

  if (!state) {
    return <Navigate to="/" replace />
  }

  const { matchId, matchLabel, seatCodes, amount, currency, showtimeId } = state

  async function handleConfirm() {
    if (!user) return
    setSubmitting(true)
    setError(null)
    try {
      const booking = await createBooking(
        { customerId: user.id, showtimeId, seatCodes, amount, currency },
        idempotencyKey,
      )

      // A fresh booking always comes back PENDING_PAYMENT (see BookingOrchestrationService's
      // note on Step 5: payment confirmation is asynchronous, so this is the only status a
      // completed create can return). CANCELLED here can only be a replay of a booking whose
      // saga already failed: booking-service releases the Redis idempotency claim on failure but
      // leaves the row keyed by this Idempotency-Key, and its findByIdempotencyKey fallback does
      // not filter by status, so a second POST with the same key answers 201 with the dead
      // booking. Sending the customer to the payment status screen for it announced a failed
      // payment for a charge never attempted, and made the retry button look like it had done
      // something. Nothing can be retried under this key — only a different set of seats will do.
      if (booking.status === 'CANCELLED') {
        setError(
          'Đơn đặt vé cho những ghế này đã bị huỷ. Vui lòng quay lại và chọn ghế khác.',
        )
        return
      }

      navigate(`/checkout/${booking.bookingId}/status`, {
        state: { matchLabel, seatCodes },
      })
    } catch (err) {
      setError(getErrorMessage(err, 'Không thể tạo đơn đặt vé. Vui lòng thử lại.'))
    } finally {
      setSubmitting(false)
    }
  }

  // Escape hatch for a failed checkout (most commonly a seat conflict: someone else booked one
  // of these seats first) — without this, the only option used to be retrying the same seats
  // forever. Releases this attempt's hold defensively (the backend has usually already released
  // it as part of compensating the failure) before sending the user back to pick again.
  function handleBackToSeatSelection() {
    unholdSeats(showtimeId, seatCodes).catch(() => {})
    navigate(`/matches/${matchId}`)
  }

  return (
    <section className="mx-auto max-w-lg px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-2xl">Xác nhận đơn đặt vé</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="rounded-lg border border-border bg-surface-2 p-4">
              <div className="font-medium">{matchLabel}</div>
              <div className="text-sm text-muted">Ghế: {seatCodes.join(', ')}</div>
            </div>

            <div className="flex items-center justify-between border-t border-border pt-4">
              <span className="text-muted">Tổng cộng</span>
              <span className="text-xl font-bold">{formatCurrency(amount, currency)}</span>
            </div>

            {error && (
              <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
                {error}
              </p>
            )}

            <Button
              variant="gradient"
              size="lg"
              disabled={submitting}
              onClick={handleConfirm}
            >
              {submitting ? 'Đang xử lý…' : 'Xác nhận & Thanh toán'}
            </Button>

            {error && (
              <Button variant="outline" size="lg" onClick={handleBackToSeatSelection}>
                Quay lại chọn ghế khác
              </Button>
            )}

            <p className="text-center text-xs text-muted">
              Bằng việc xác nhận, đơn đặt vé sẽ được tạo và chuyển sang bước thanh
              toán. Bạn đang đăng nhập với{' '}
              <Link to="/account" className="text-accent hover:underline">
                {user?.email}
              </Link>
              .
            </p>
          </CardContent>
        </Card>
      </motion.div>
    </section>
  )
}
