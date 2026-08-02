import { motion } from 'framer-motion'
import { Clock, MapPin } from 'lucide-react'
import { memo, useMemo } from 'react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card'
import { useCountdown } from '@/hooks/useCountdown'
import { formatKickoff } from '@/lib/format'
import { memoComponent } from '@/lib/memo'
import { cn } from '@/lib/utils'
import { catalogStatus, nearestShowtime, teamInitials, ticketsRemaining, type CatalogStatus } from './matchView'
import type { Match } from './types'

const statusMeta: Record<CatalogStatus, { label: string; variant: 'accent' | 'warning' | 'danger' }> = {
  closed: { label: 'Đã kết thúc', variant: 'danger' },
  on_sale: { label: 'Đang mở bán', variant: 'accent' },
  few_left: { label: 'Sắp hết vé', variant: 'warning' },
  sold_out: { label: 'Hết vé', variant: 'danger' },
}

function TeamBadge({ initials }: { initials: string }) {
  return (
    <div className="flex size-14 items-center justify-center rounded-full bg-surface-2 text-lg font-bold text-gradient-brand ring-1 ring-border">
      {initials}
    </div>
  )
}

const MemoizedTeamBadge = memoComponent(TeamBadge)

function MatchCardComponent({ match }: { match: Match }) {
  const { showtime, catalogState, remainingTickets } = useMemo(() => ({
    showtime: nearestShowtime(match),
    catalogState: catalogStatus(match),
    remainingTickets: ticketsRemaining(match),
  }), [match])
  const status = statusMeta[catalogState]
  const bookingClosed = catalogState === 'sold_out' || catalogState === 'closed'
  const countdown = useCountdown(showtime?.startTime ?? match.createdAt)
  const showCountdown = !bookingClosed && !countdown.isPast && countdown.days < 3

  return (
    <motion.div
      whileHover={{ y: -6 }}
      transition={{ type: 'spring', stiffness: 300, damping: 22 }}
      className="h-full"
    >
      <Card
        className={cn(
          'group flex h-full flex-col overflow-hidden transition-shadow duration-300 hover:shadow-glow-accent',
          bookingClosed && 'opacity-60',
        )}
      >
        <CardHeader className="flex-row items-center justify-between gap-2 space-y-0 pb-4">
          <Badge variant="outline" className="text-muted">
            {match.competition}
          </Badge>
          <Badge variant={status.variant}>{status.label}</Badge>
        </CardHeader>

        <CardContent className="flex flex-1 flex-col gap-5 pt-0">
          <div className="flex items-center justify-between gap-3">
            <div className="flex flex-1 flex-col items-center gap-2 text-center">
              <MemoizedTeamBadge initials={teamInitials(match.homeTeam)} />
              <span className="text-sm font-medium">{match.homeTeam}</span>
            </div>
            <span className="text-xs font-semibold text-muted">VS</span>
            <div className="flex flex-1 flex-col items-center gap-2 text-center">
              <MemoizedTeamBadge initials={teamInitials(match.awayTeam)} />
              <span className="text-sm font-medium">{match.awayTeam}</span>
            </div>
          </div>

          {showtime && (
            <div className="flex flex-col gap-1.5 text-sm text-muted">
              <div className="flex items-center gap-2">
                <Clock className="size-4 text-accent" />
                <span>{formatKickoff(showtime.startTime)}</span>
              </div>
              <div className="flex items-center gap-2">
                <MapPin className="size-4 text-accent" />
                <span>{showtime.stadiumName}</span>
              </div>
            </div>
          )}

          {showCountdown && (
            <div className="flex items-center gap-1 rounded-lg border border-border bg-surface-2 px-3 py-2 font-mono text-sm animate-pulse-glow">
              <span className="text-accent">⏱</span>
              {countdown.days > 0 && <span>{countdown.days}n</span>}
              <span>{String(countdown.hours).padStart(2, '0')}:</span>
              <span>{String(countdown.minutes).padStart(2, '0')}:</span>
              <span>{String(countdown.seconds).padStart(2, '0')}</span>
              <span className="ml-1 text-xs text-muted">đến giờ bóng lăn</span>
            </div>
          )}
        </CardContent>

        <CardFooter className="flex items-center justify-between gap-3">
          <div className="text-sm text-muted">
            {catalogState === 'closed'
              ? 'Đã kết thúc'
              : remainingTickets > 0
              ? `Còn ${remainingTickets.toLocaleString('vi-VN')} vé`
              : 'Đã hết vé'}
          </div>
          {bookingClosed ? (
            <Button variant="outline" disabled>
              {status.label}
            </Button>
          ) : (
            <Button asChild variant="gradient">
              <Link to={`/matches/${match.matchId}`}>Chọn vé</Link>
            </Button>
          )}
        </CardFooter>
      </Card>
    </motion.div>
  )
}

export const MatchCard = memo(MatchCardComponent)
