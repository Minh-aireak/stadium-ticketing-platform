import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { Loader2 } from 'lucide-react'
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { getSeatMap } from '@/features/seats/seatsApi'
import { SeatMap } from '@/features/seats/SeatMap'
import type { Seat } from '@/features/seats/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { formatCurrency } from '@/lib/format'
import type { CheckoutState } from './CheckoutPage'

const MAX_SEATS = 8

export interface SeatSelectionState {
  matchLabel: string
  showtimeId: string
}

export function SeatSelectionPage() {
  const { matchId } = useParams<{ matchId: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { toast } = useToast()
  const state = location.state as SeatSelectionState | null

  const [seats, setSeats] = useState<Seat[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [selected, setSelected] = useState<string[]>([])

  useEffect(() => {
    if (!state?.showtimeId) return
    let cancelled = false
    setLoading(true)
    setFailed(false)

    getSeatMap(state.showtimeId)
      .then((data) => {
        if (!cancelled) setSeats(data.seats)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        toast({
          title: 'Không thể tải sơ đồ ghế',
          description: getErrorMessage(err),
          variant: 'error',
        })
        setFailed(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [state?.showtimeId, toast])

  // No showtimeId to load from (e.g. a direct/reloaded URL) — the match can have several
  // showtimes, so there's no safe default to fall back to; send the user to pick one again.
  if (!matchId || !state?.showtimeId) {
    return <Navigate to={matchId ? `/matches/${matchId}` : '/'} replace />
  }

  if (failed) {
    return <Navigate to={`/matches/${matchId}`} replace />
  }

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
    if (!state?.showtimeId) return
    const checkoutState: CheckoutState = {
      matchId: matchId!,
      matchLabel: state.matchLabel,
      showtimeId: state.showtimeId,
      seatCodes: selected,
      amount: total,
      currency: 'VND',
    }
    navigate('/checkout', { state: checkoutState })
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
            {state.matchLabel} — tối đa {MAX_SEATS} ghế mỗi lượt đặt
          </p>
        </div>

        <Card>
          <CardContent className="overflow-x-auto p-6">
            {loading ? (
              <div className="flex justify-center py-12">
                <Loader2 className="size-8 animate-spin text-accent" />
              </div>
            ) : (
              <SeatMap seats={seats} selected={selected} onToggle={toggleSeat} />
            )}
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
