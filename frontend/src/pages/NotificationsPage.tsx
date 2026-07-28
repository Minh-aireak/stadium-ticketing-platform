import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { Bell, Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { listMyNotifications, markNotificationRead } from '@/features/notifications/notificationsApi'
import type { Notification } from '@/features/notifications/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 20

const relativeFormatter = new Intl.RelativeTimeFormat('vi-VN', { numeric: 'auto' })

function relativeTime(iso: string): string {
  const diffHours = Math.round((new Date(iso).getTime() - Date.now()) / (1000 * 60 * 60))
  if (Math.abs(diffHours) < 24) return relativeFormatter.format(diffHours, 'hour')
  return relativeFormatter.format(Math.round(diffHours / 24), 'day')
}

export function NotificationsPage() {
  const { toast } = useToast()
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listMyNotifications({ page, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return
        setNotifications(res.items)
        setTotalElements(res.totalElements)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = getErrorMessage(err, 'Không thể tải thông báo.')
        setError(message)
        toast({ title: 'Không thể tải thông báo', description: message, variant: 'error' })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [page, toast])

  async function handleMarkRead(notification: Notification) {
    if (notification.read) return
    setNotifications((prev) =>
      prev.map((n) => (n.notificationId === notification.notificationId ? { ...n, read: true } : n)),
    )
    try {
      await markNotificationRead(notification.notificationId)
    } catch (err) {
      setNotifications((prev) =>
        prev.map((n) => (n.notificationId === notification.notificationId ? { ...n, read: false } : n)),
      )
      toast({
        title: 'Không thể đánh dấu đã đọc',
        description: getErrorMessage(err),
        variant: 'error',
      })
    }
  }

  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))

  return (
    <section className="mx-auto max-w-2xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-6"
      >
        <h1 className="text-2xl font-bold">Thông báo</h1>

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

        {!loading && !error && notifications.length === 0 && (
          <p className="text-center text-muted">Bạn chưa có thông báo nào.</p>
        )}

        {!loading && !error && notifications.length > 0 && (
          <>
            <div className="flex flex-col gap-3">
              {notifications.map((n) => (
                <Card
                  key={n.notificationId}
                  className={cn(!n.read && 'cursor-pointer border-accent/50')}
                  onClick={() => handleMarkRead(n)}
                >
                  <CardContent className="flex items-start gap-3 p-5">
                    <div
                      className={cn(
                        'flex size-9 shrink-0 items-center justify-center rounded-full',
                        n.read ? 'bg-surface-2 text-muted' : 'bg-accent/20 text-accent',
                      )}
                    >
                      <Bell className="size-4" />
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium">{n.title}</span>
                        <span className="text-xs text-muted">{relativeTime(n.createdAt)}</span>
                      </div>
                      <p className="text-sm text-muted">{n.body}</p>
                    </div>
                    {!n.read && <span className="mt-1.5 size-2 shrink-0 rounded-full bg-accent" />}
                  </CardContent>
                </Card>
              ))}
            </div>

            {totalPages > 1 && (
              <div className="flex items-center justify-center gap-4">
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
      </motion.div>
    </section>
  )
}
