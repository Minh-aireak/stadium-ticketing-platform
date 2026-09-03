import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { Loader2, QrCode, User } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { useAuth } from '@/features/auth/AuthContext'
import { listMyBookings } from '@/features/booking/bookingApi'
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

export function AccountPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { toast } = useToast()

  const [bookings, setBookings] = useState<BookingSummary[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

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
                {bookings.map((booking) => (
                  <Card key={booking.bookingId}>
                    <CardContent className="flex items-center justify-between gap-4 p-6">
                      <div className="flex items-center gap-4">
                        <QrCode className="size-10 text-accent" />
                        <div>
                          <div className="font-medium">
                            Ghế {booking.seatCodes.join(', ')} · {formatCurrency(booking.amount)}
                          </div>
                          <div className="text-sm text-muted">
                            Mã đặt vé {booking.bookingId} · Mua ngày{' '}
                            {dateFormatter.format(new Date(booking.createdAt))}
                          </div>
                        </div>
                      </div>
                      <Badge variant={statusMeta[booking.status].variant}>
                        {statusMeta[booking.status].label}
                      </Badge>
                    </CardContent>
                  </Card>
                ))}
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
