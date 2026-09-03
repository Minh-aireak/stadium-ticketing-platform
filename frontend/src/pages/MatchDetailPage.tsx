import { useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { Clock, Loader2, MapPin, Users } from 'lucide-react'
import { Link, Navigate, useParams } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getMatch } from '@/features/matches/matchesApi'
import { ticketsRemaining, upcomingShowtimes } from '@/features/matches/matchView'
import type { Match } from '@/features/matches/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { formatCurrency, formatKickoff } from '@/lib/format'
import type { SeatSelectionState } from './SeatSelectionPage'

export function MatchDetailPage() {
  const { matchId } = useParams<{ matchId: string }>()
  const { toast } = useToast()
  const [match, setMatch] = useState<Match | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  // GET /matches/{matchId} answers for a match in any status — only the browse list filters to
  // PUBLISHED (see MatchCatalogService#listMatches) — so a customer who bookmarked this page, or
  // who was on it when an admin cancelled the match, is served a DRAFT/COMPLETED/CANCELLED match
  // here. This page read only each showtime's clock and its seat count, both of which a cancelled
  // match keeps, so it went on offering "Chọn ghế" for one. catalogStatus() already holds the rule
  // for the match card — a non-PUBLISHED match is 'closed', pinned by matchView.test.ts — and this
  // is the same rule, stated per status so the customer is told which of the three happened rather
  // than the card's single "Đã kết thúc".
  const saleClosed = useMemo(() => {
    if (!match || match.status === 'PUBLISHED') return null
    if (match.status === 'CANCELLED') return { headline: 'Trận đấu đã bị huỷ', action: 'Đã huỷ' }
    if (match.status === 'COMPLETED') return { headline: 'Trận đấu đã kết thúc', action: 'Đã kết thúc' }
    return { headline: 'Trận đấu chưa mở bán', action: 'Chưa mở bán' }
  }, [match])
  const matchView = useMemo(() => {
    if (!match) return { showtimes: [], upcomingCount: 0, remainingTickets: 0 }
    const upcoming = upcomingShowtimes(match)
    return {
      showtimes: match.showtimes.toSorted(
        (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime(),
      ),
      upcomingCount: upcoming.length,
      remainingTickets: ticketsRemaining(match),
    }
  }, [match])

  useEffect(() => {
    if (!matchId) return
    let cancelled = false
    setLoading(true)
    setNotFound(false)

    getMatch(matchId)
      .then((data) => {
        if (!cancelled) setMatch(data)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if ((err as { response?: { status?: number } })?.response?.status === 404) {
          setNotFound(true)
          return
        }
        toast({
          title: 'Không thể tải trận đấu',
          description: getErrorMessage(err),
          variant: 'error',
        })
        setNotFound(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [matchId, toast])

  if (!matchId || notFound) {
    return <Navigate to="/" replace />
  }

  if (loading || !match) {
    return (
      <section className="mx-auto flex max-w-4xl justify-center px-4 py-24 sm:px-6">
        <Loader2 className="size-8 animate-spin text-accent" />
      </section>
    )
  }

  return (
    <section className="mx-auto max-w-4xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <div className="mb-6 flex items-center gap-2">
          <Badge variant="outline">{match.competition}</Badge>
        </div>

        <div className="flex flex-col items-center gap-3 text-center">
          <h1 className="text-3xl font-bold sm:text-4xl">
            {match.homeTeam} <span className="text-muted">vs</span> {match.awayTeam}
          </h1>
          <div className="flex items-center justify-center gap-2 text-sm text-muted">
            <Users className="size-4 text-accent" />
            {saleClosed
              ? saleClosed.headline
              : matchView.upcomingCount === 0
              ? 'Trận đấu đã kết thúc'
              : matchView.remainingTickets > 0
              ? `Còn ${matchView.remainingTickets.toLocaleString('vi-VN')} vé`
              : 'Đã hết vé'}
          </div>
        </div>

        <div className="mt-10 flex flex-col gap-4">
          {matchView.showtimes.map((showtime) => {
            const showtimeStarted = new Date(showtime.startTime).getTime() <= Date.now()
            const showtimeSoldOut = showtime.availableSeats <= 0
            const seatSelectionState: SeatSelectionState = {
              matchLabel: `${match.homeTeam} vs ${match.awayTeam}`,
              showtimeId: showtime.showtimeId,
              startTime: showtime.startTime,
              stadiumId: showtime.stadiumId,
              currency: showtime.currency,
            }
            return (
              <Card key={showtime.showtimeId}>
                <CardContent className="flex flex-col gap-3 p-6 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex flex-col gap-1.5 text-sm text-muted">
                    <span className="flex items-center gap-2">
                      <Clock className="size-4 text-accent" />
                      {formatKickoff(showtime.startTime)}
                    </span>
                    <span className="flex items-center gap-2">
                      <MapPin className="size-4 text-accent" />
                      {showtime.stadiumName}
                    </span>
                    <span className="text-xs">Giá từ {formatCurrency(showtime.basePrice, showtime.currency)}</span>
                  </div>
                  <div className="flex flex-col items-end gap-2">
                    <div className="text-sm font-medium">
                      {showtime.availableSeats.toLocaleString('vi-VN')} /{' '}
                      {showtime.totalSeats.toLocaleString('vi-VN')} ghế trống
                    </div>
                    {saleClosed ? (
                      <Button variant="outline" size="sm" disabled>
                        {saleClosed.action}
                      </Button>
                    ) : showtimeStarted ? (
                      <Button variant="outline" size="sm" disabled>
                        Đã diễn ra
                      </Button>
                    ) : showtimeSoldOut ? (
                      <Button variant="outline" size="sm" disabled>
                        Đã hết vé
                      </Button>
                    ) : (
                      <Button asChild variant="gradient" size="sm">
                        <Link to={`/matches/${match.matchId}/seats`} state={seatSelectionState}>
                          Chọn ghế
                        </Link>
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </motion.div>
    </section>
  )
}
