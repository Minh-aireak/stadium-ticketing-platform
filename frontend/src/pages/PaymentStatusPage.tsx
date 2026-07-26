import { useEffect, useRef, useState } from 'react'
import { isAxiosError } from 'axios'
import { motion } from 'framer-motion'
import { CheckCircle2, Loader2, XCircle } from 'lucide-react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getBooking } from '@/features/booking/bookingApi'
import type { BookingStatusResponse } from '@/features/booking/types'
import { getPaymentStatus } from '@/features/payment/paymentApi'
import type { PaymentStatusResponse } from '@/features/payment/types'

const POLL_INTERVAL_MS = 2500

type ViewState = 'pending' | 'success' | 'failed'

function resolveView(
  booking: BookingStatusResponse | null,
  payment: PaymentStatusResponse | null,
): ViewState {
  if (payment?.status === 'SUCCEEDED' || booking?.status === 'CONFIRMED') return 'success'
  if (payment?.status === 'FAILED' || booking?.status === 'CANCELLED') return 'failed'
  return 'pending'
}

export function PaymentStatusPage() {
  const { bookingId } = useParams<{ bookingId: string }>()
  const location = useLocation()
  const context = location.state as { matchLabel?: string; seatCodes?: string[] } | null

  const [booking, setBooking] = useState<BookingStatusResponse | null>(null)
  const [payment, setPayment] = useState<PaymentStatusResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const view = resolveView(booking, payment)
  const viewRef = useRef(view)

  useEffect(() => {
    viewRef.current = view
  }, [view])

  useEffect(() => {
    if (!bookingId) return
    let cancelled = false

    async function poll() {
      try {
        const [bookingRes, paymentRes] = await Promise.allSettled([
          getBooking(bookingId!),
          getPaymentStatus(bookingId!),
        ])
        if (cancelled) return
        if (bookingRes.status === 'fulfilled') setBooking(bookingRes.value)
        if (paymentRes.status === 'fulfilled') {
          setPayment(paymentRes.value)
        } else if (!isAxiosError(paymentRes.reason) || paymentRes.reason.response?.status !== 404) {
          // A 404 right after creation just means the payment record hasn't landed yet — keep polling.
          setError('Không thể tải trạng thái thanh toán.')
        }
      } catch {
        if (!cancelled) setError('Không thể tải trạng thái đơn hàng.')
      }
    }

    poll()
    const id = setInterval(() => {
      if (viewRef.current !== 'pending') {
        clearInterval(id)
        return
      }
      poll()
    }, POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      clearInterval(id)
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
            {view === 'pending' && (
              <>
                <Loader2 className="size-12 animate-spin text-accent" />
                <h1 className="text-xl font-bold">Đang xử lý thanh toán…</h1>
                <p className="text-sm text-muted">
                  Đơn đặt vé <span className="font-mono">{bookingId}</span> đang chờ xác
                  nhận từ cổng thanh toán. Trang này sẽ tự cập nhật.
                </p>
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
