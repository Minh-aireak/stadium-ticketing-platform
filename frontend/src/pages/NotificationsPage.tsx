import { motion } from 'framer-motion'
import { Bell } from 'lucide-react'

import { Card, CardContent } from '@/components/ui/card'
import { mockNotifications } from '@/features/notifications/mockNotifications'
import { cn } from '@/lib/utils'

const relativeFormatter = new Intl.RelativeTimeFormat('vi-VN', { numeric: 'auto' })

function relativeTime(iso: string): string {
  const diffHours = Math.round((new Date(iso).getTime() - Date.now()) / (1000 * 60 * 60))
  if (Math.abs(diffHours) < 24) return relativeFormatter.format(diffHours, 'hour')
  return relativeFormatter.format(Math.round(diffHours / 24), 'day')
}

export function NotificationsPage() {
  return (
    <section className="mx-auto max-w-2xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-6"
      >
        <h1 className="text-2xl font-bold">Thông báo</h1>

        <div className="flex flex-col gap-3">
          {mockNotifications.map((n) => (
            <Card key={n.id} className={cn(!n.read && 'border-accent/50')}>
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
      </motion.div>
    </section>
  )
}
