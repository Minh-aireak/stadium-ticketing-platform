import { motion } from 'framer-motion'
import { QrCode, User } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { useAuth } from '@/features/auth/AuthContext'
import { mockTickets } from '@/features/account/mockTickets'

const dateFormatter = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' })

export function AccountPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/')
  }

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
          <div className="flex flex-col gap-4">
            {mockTickets.map((ticket) => (
              <Card key={ticket.bookingId}>
                <CardContent className="flex items-center justify-between gap-4 p-6">
                  <div className="flex items-center gap-4">
                    <QrCode className="size-10 text-accent" />
                    <div>
                      <div className="font-medium">{ticket.matchLabel}</div>
                      <div className="text-sm text-muted">
                        Ghế {ticket.seatCodes.join(', ')} · Mua ngày{' '}
                        {dateFormatter.format(new Date(ticket.purchasedAt))}
                      </div>
                    </div>
                  </div>
                  <Badge variant={ticket.status === 'CONFIRMED' ? 'default' : 'warning'}>
                    {ticket.status === 'CONFIRMED' ? 'Đã xác nhận' : 'Chờ thanh toán'}
                  </Badge>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>
      </motion.div>
    </section>
  )
}
