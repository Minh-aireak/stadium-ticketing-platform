import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { Navigate, useNavigate, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getMockMatchById } from '@/features/matches/mockMatches'
import { generateMockSeatMap } from '@/features/seats/mockSeatMap'
import { SeatMap } from '@/features/seats/SeatMap'
import { formatCurrency } from '@/lib/format'
import type { CheckoutState } from './CheckoutPage'

const MAX_SEATS = 8

export function SeatSelectionPage() {
  const { matchId } = useParams<{ matchId: string }>()
  const navigate = useNavigate()
  const match = matchId ? getMockMatchById(matchId) : undefined
  const [selected, setSelected] = useState<string[]>([])

  // TODO: showtimeId should come from match-catalog-service once it exposes a
  // GET /matches/{id}/showtimes read endpoint — there's currently only a write side.
  const showtimeId = `${matchId}-showtime-1`
  const seats = useMemo(
    () => (match ? generateMockSeatMap(showtimeId, match.fromPrice) : []),
    [match, showtimeId],
  )

  if (!match) {
    return <Navigate to="/" replace />
  }

  const matchLabel = `${match.homeTeam} vs ${match.awayTeam}`
  const selectedSeats = seats.filter((s) => selected.includes(s.code))
  const total = selectedSeats.reduce((sum, s) => sum + s.price, 0)

  function toggleSeat(code: string) {
    setSelected((prev) => {
      if (prev.includes(code)) return prev.filter((c) => c !== code)
      if (prev.length >= MAX_SEATS) return prev
      return [...prev, code]
    })
  }

  function handleContinue() {
    const state: CheckoutState = {
      matchId: matchId!,
      matchLabel,
      showtimeId,
      seatCodes: selected,
      amount: total,
      currency: 'VND',
    }
    navigate('/checkout', { state })
  }

  return (
    <section className="mx-auto max-w-4xl px-4 py-12 sm:px-6">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-8"
      >
        <div className="text-center">
          <h1 className="text-2xl font-bold sm:text-3xl">Chọn ghế</h1>
          <p className="text-muted">
            {match.homeTeam} vs {match.awayTeam} — tối đa {MAX_SEATS} ghế mỗi lượt đặt
          </p>
        </div>

        <Card>
          <CardContent className="overflow-x-auto p-6">
            <SeatMap seats={seats} selected={selected} onToggle={toggleSeat} />
          </CardContent>
        </Card>

        <Card className="sticky bottom-4">
          <CardContent className="flex flex-col items-center justify-between gap-4 p-6 sm:flex-row">
            <div className="text-center sm:text-left">
              <div className="text-sm text-muted">
                {selectedSeats.length > 0
                  ? `Đã chọn: ${selectedSeats.map((s) => s.code).join(', ')}`
                  : 'Chưa chọn ghế nào'}
              </div>
              <div className="text-xl font-bold">{formatCurrency(total)}</div>
            </div>
            <Button
              variant="gradient"
              size="lg"
              disabled={selectedSeats.length === 0}
              onClick={handleContinue}
            >
              Tiếp tục thanh toán
            </Button>
          </CardContent>
        </Card>
      </motion.div>
    </section>
  )
}
