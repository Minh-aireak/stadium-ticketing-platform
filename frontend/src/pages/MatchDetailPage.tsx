import { motion } from 'framer-motion'
import { Clock, MapPin } from 'lucide-react'
import { Link, Navigate, useParams } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getMockMatchById } from '@/features/matches/mockMatches'
import { formatCurrency, formatKickoff } from '@/lib/format'

const TIERS = [
  { name: 'Khán đài Phổ thông', multiplier: 1 },
  { name: 'Khán đài Cao cấp', multiplier: 1.5 },
  { name: 'VIP sát sân', multiplier: 2.2 },
]

export function MatchDetailPage() {
  const { matchId } = useParams<{ matchId: string }>()
  const match = matchId ? getMockMatchById(matchId) : undefined

  if (!match) {
    return <Navigate to="/" replace />
  }

  const soldOut = match.status === 'sold_out'

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
          <div className="flex flex-col gap-1.5 text-sm text-muted sm:flex-row sm:gap-6">
            <span className="flex items-center justify-center gap-2">
              <Clock className="size-4 text-accent" />
              {formatKickoff(match.kickoffAt)}
            </span>
            <span className="flex items-center justify-center gap-2">
              <MapPin className="size-4 text-accent" />
              {match.stadium}
            </span>
          </div>
        </div>

        <div className="mt-10 grid gap-4 sm:grid-cols-3">
          {TIERS.map((tier) => (
            <Card key={tier.name}>
              <CardContent className="flex flex-col items-center gap-2 p-6 text-center">
                <span className="text-sm text-muted">{tier.name}</span>
                <span className="text-xl font-bold">
                  {formatCurrency(Math.round((match.fromPrice * tier.multiplier) / 1000) * 1000)}
                </span>
              </CardContent>
            </Card>
          ))}
        </div>

        <div className="mt-10 flex justify-center">
          {soldOut ? (
            <Button variant="outline" size="lg" disabled>
              Đã hết vé
            </Button>
          ) : (
            <Button asChild variant="gradient" size="lg">
              <Link to={`/matches/${match.id}/seats`}>Chọn ghế</Link>
            </Button>
          )}
        </div>
      </motion.div>
    </section>
  )
}
