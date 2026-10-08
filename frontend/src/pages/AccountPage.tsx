import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { Loader2, QrCode, User } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { useAuth } from '@/features/auth/AuthContext'
import { cancelBooking, listMyBookings } from '@/features/booking/bookingApi'
import type { BookingStatus, BookingSummary } from '@/features/booking/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { formatCurrency } from '@/lib/format'

const PAGE_SIZE = 10

const statusMeta: Record<BookingStatus, { label: string; variant: 'default' | 'warning' | 'danger' | 'outline' }> = {
  CONFIRMED: { label: 'Đã xác nhận', variant: 'default' },
  PENDING_PAYMENT: { label: 'Chờ thanh toán', variant: 'warning' },
  CANCELLED: { label: 'Đã huỷ', variant: 'danger' },
  DRAFT: { label: 'Nháp', variant: 'outline' },
}

const dateFormatter = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' })

// Seats a booking still holds: every seat it was made for, minus the cancelled ones (all of them,
// once it is CANCELLED). `?? []` keeps a list from an older booking-service readable.
function activeSeats(booking: BookingSummary): string[] {
  const cancelled = booking.cancelledSeatCodes ?? []
  return booking.seatCodes.filter((code) => !cancelled.includes(code))
}

export function AccountPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { toast } = useToast()

  const [bookings, setBookings] = useState<BookingSummary[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // The booking whose cancel request is in flight, so its button reads "Đang huỷ…" and cannot be
  // clicked twice. One at a time is enough: a customer cancels one booking at a time.
  const [cancellingId, setCancellingId] = useState<string | null>(null)
  // The paid booking whose seat picker is open, and the seats ticked in it.
  const [selecting, setSelecting] = useState<{ bookingId: string; seats: string[] } | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listMyBookings({ page, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return
        setBookings(res.items)
        setTotalElements(res.totalElements)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = getErrorMessage(err, 'Không thể tải danh sách vé.')
        setError(message)
        toast({ title: 'Không thể tải vé của bạn', description: message, variant: 'error' })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [page, toast])

  // FR-21. An unpaid booking is cancelled whole ("Huỷ đơn"); a paid one seat by seat ("Huỷ vé"),
  // until 24 hours before kickoff, each seat refunded at its own price. The confirm() is the whole
  // safeguard against a mis-click: the seats go back on sale the moment booking-service answers,
  // and another customer can take them, so there is no undo. The list is patched in place from the
  // response rather than refetched: the page the customer is on stays put, and the row changes
  // right where they were looking.
  async function handleCancel(booking: BookingSummary, seatCodes?: string[]) {
    const paid = booking.status === 'CONFIRMED'
    const seats = (seatCodes ?? activeSeats(booking)).join(', ')
    const question = paid
      ? `Huỷ vé ghế ${seats}? Bạn được hoàn 100% giá vé các ghế này, ghế sẽ được mở bán lại và không thể hoàn tác.`
      : `Huỷ đơn ghế ${seats}? Ghế sẽ được trả lại cho người khác và không thể hoàn tác.`
    if (!window.confirm(question)) {
      return
    }
    setCancellingId(booking.bookingId)
    try {
      const updated = await cancelBooking(booking.bookingId, seatCodes)
      setBookings((current) =>
        current.map((b) =>
          b.bookingId === updated.bookingId
            ? {
                ...b,
                status: updated.status,
                cancelledSeatCodes: updated.cancelledSeatCodes,
                refundedAmount: updated.refundedAmount,
              }
            : b,
        ),
      )
      setSelecting(null)
      toast({
        title: paid ? 'Đã huỷ vé' : 'Đã huỷ đơn',
        description: paid
          ? `Ghế ${seats} đã được mở bán lại. Đã hoàn tổng cộng ${formatCurrency(updated.refundedAmount, updated.currency)}.`
          : `Ghế ${seats} đã được trả lại.`,
      })
    } catch (err: unknown) {
      toast({
        title: paid ? 'Không thể huỷ vé' : 'Không thể huỷ đơn',
        description: getErrorMessage(err, 'Vui lòng tải lại trang và thử lại.'),
        variant: 'error',
      })
    } finally {
      setCancellingId(null)
    }
  }

  function toggleSeat(code: string) {
    setSelecting((current) =>
      current === null
        ? current
        : {
            ...current,
            seats: current.seats.includes(code)
              ? current.seats.filter((seat) => seat !== code)
              : [...current.seats, code],
          },
    )
  }

  async function handleLogout() {
    try {
      await logout()
    } catch (err: unknown) {
      // The local session is gone either way (see AuthContext#logout), but the server may still
      // hold the refresh session, so say so rather than letting the rejection go unhandled.
      toast({
        title: 'Đăng xuất chưa hoàn tất',
        description: getErrorMessage(err, 'Không thể báo cho máy chủ. Vui lòng thử đăng xuất lại.'),
        variant: 'error',
      })
    } finally {
      navigate('/')
    }
  }

  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))

  return (
    <section className="mx-auto max-w-3xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-8"
      >
        <Card>
          <CardContent className="flex items-center justify-between gap-4 p-6">
            <div className="flex items-center gap-3">
              <div className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-accent">
                <User className="size-6" />
              </div>
              <div>
                <div className="font-medium">{user?.email}</div>
                <div className="text-xs text-muted">ID: {user?.id}</div>
              </div>
            </div>
            <Button variant="outline" size="sm" onClick={handleLogout}>
              Đăng xuất
            </Button>
          </CardContent>
        </Card>

        <div>
          <h2 className="mb-4 text-xl font-bold">Vé của tôi</h2>

          {loading && (
            <div className="flex justify-center py-12">
              <Loader2 className="size-8 animate-spin text-accent" />
            </div>
          )}

          {!loading && error && (
            <p className="rounded-lg border border-danger/40 bg-danger/10 px-4 py-3 text-center text-sm text-danger">
              {error}
            </p>
          )}

          {!loading && !error && bookings.length === 0 && (
            <p className="text-center text-muted">Bạn chưa có vé nào.</p>
          )}

          {!loading && !error && bookings.length > 0 && (
            <>
              <div className="flex flex-col gap-4">
                {bookings.map((booking) => {
                  const cancelled = booking.cancelledSeatCodes ?? []
                  const picker = selecting?.bookingId === booking.bookingId ? selecting : null
                  return (
                    <Card key={booking.bookingId}>
                      <CardContent className="flex items-center justify-between gap-4 p-6">
                        <div className="flex items-center gap-4">
                          <QrCode className="size-10 text-accent" />
                          <div>
                            <div className="font-medium">
                              Ghế{' '}
                              {booking.seatCodes.map((code, index) => (
                                <span key={code} className={cancelled.includes(code) ? 'text-muted line-through' : undefined}>
                                  {code}
                                  {index < booking.seatCodes.length - 1 ? ', ' : ''}
                                </span>
                              ))}{' '}
                              · {formatCurrency(booking.amount, booking.currency)}
                            </div>
                            <div className="text-sm text-muted">
                              Mã đặt vé {booking.bookingId} · Mua ngày{' '}
                              {dateFormatter.format(new Date(booking.createdAt))}
                            </div>
                            {(booking.refundedAmount ?? 0) > 0 && (
                              <div className="text-sm text-muted">
                                Đã hoàn {formatCurrency(booking.refundedAmount, booking.currency)}
                              </div>
                            )}
                          </div>
                        </div>
                        <div className="flex items-center gap-3">
                          <Badge variant={statusMeta[booking.status].variant}>
                            {statusMeta[booking.status].label}
                          </Badge>
                          {booking.status === 'PENDING_PAYMENT' && (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={cancellingId !== null}
                              onClick={() => void handleCancel(booking)}
                            >
                              {cancellingId === booking.bookingId ? 'Đang huỷ…' : 'Huỷ đơn'}
                            </Button>
                          )}
                          {booking.status === 'CONFIRMED' && activeSeats(booking).length > 0 && (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={cancellingId !== null}
                              aria-expanded={picker !== null}
                              onClick={() => setSelecting(picker ? null : { bookingId: booking.bookingId, seats: [] })}
                            >
                              Huỷ vé
                            </Button>
                          )}
                        </div>
                      </CardContent>
                      {picker && (
                        <div className="flex flex-col gap-3 border-t border-border px-6 py-4">
                          <p className="text-sm text-muted">
                            Chọn ghế muốn huỷ. Mỗi ghế được hoàn 100% giá vé; chỉ huỷ được trước giờ đá 24 giờ.
                          </p>
                          <div className="flex flex-wrap gap-2">
                            {activeSeats(booking).map((code) => (
                              <Button
                                key={code}
                                size="sm"
                                variant={picker.seats.includes(code) ? 'default' : 'outline'}
                                aria-pressed={picker.seats.includes(code)}
                                onClick={() => toggleSeat(code)}
                              >
                                {code}
                              </Button>
                            ))}
                          </div>
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              disabled={picker.seats.length === 0 || cancellingId !== null}
                              onClick={() => void handleCancel(booking, picker.seats)}
                            >
                              {cancellingId === booking.bookingId ? 'Đang huỷ…' : `Huỷ ${picker.seats.length} ghế`}
                            </Button>
                            <Button size="sm" variant="ghost" onClick={() => setSelecting(null)}>
                              Đóng
                            </Button>
                          </div>
                        </div>
                      )}
                    </Card>
                  )
                })}
              </div>

              {totalPages > 1 && (
                <div className="mt-6 flex items-center justify-center gap-4">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page === 0}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    Trước
                  </Button>
                  <span className="text-sm text-muted">
                    Trang {page + 1}/{totalPages}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page + 1 >= totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Sau
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </motion.div>
    </section>
  )
}
