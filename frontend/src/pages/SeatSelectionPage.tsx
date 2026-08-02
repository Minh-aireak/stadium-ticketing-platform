import { motion } from 'framer-motion'
import { Loader2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SeatMap } from '@/features/seats/SeatMap'
import { StadiumSeatMap } from '@/features/seats/StadiumSeatMap'
import { getSeatLayout, getSeatMap, holdSeats, unholdSeats } from '@/features/seats/seatsApi'
import { buildStadiumMap } from '@/features/seats/stadiumLayout'
import type { Seat, SeatLayout } from '@/features/seats/types'
import { useToast } from '@/hooks/useToast'
import { getErrorMessage } from '@/lib/errors'
import { formatCurrency } from '@/lib/format'
import { memoComponent, useMemoizedKeyMap, useMemoizedSet } from '@/lib/memo'
import type { CheckoutState } from './CheckoutPage'

const MAX_SEATS = 8

export interface SeatSelectionState {
  matchLabel: string
  showtimeId: string
  startTime: string
  stadiumId: string
}

type TierFilter = 'all' | Seat['tier']

function getTierLabel(tier: TierFilter) {
  if (tier === 'all') return 'Tất cả'
  if (tier === 'standard') return 'Standard'
  if (tier === 'premium') return 'Premium'
  return 'VIP'
}

const statusLegend = [
  { label: 'Còn trống', className: 'border border-border bg-surface-2' },
  { label: 'Đang chọn', className: 'border border-primary bg-primary shadow-glow-primary' },
  { label: 'Đang giữ', className: 'border border-warning bg-warning/30' },
  { label: 'Đã bán', className: 'border border-danger bg-danger/20' },
]

const tierLegend: { tier: Seat['tier']; label: string; className: string }[] = [
  { tier: 'vip', label: 'VIP', className: 'bg-accent-2' },
  { tier: 'premium', label: 'Premium', className: 'bg-accent' },
  { tier: 'standard', label: 'Standard', className: 'bg-border' },
]

function SeatGuideComponent({ seats }: { seats: Seat[] }) {
  const pricesByTier = useMemo(() => {
    const prices = new Map<Seat['tier'], number>()
    seats.forEach((seat) => {
      if (!prices.has(seat.tier)) prices.set(seat.tier, seat.price)
    })
    return prices
  }, [seats])

  return (
    <Card>
      <CardContent className="flex flex-col gap-4 p-4">
        <div>
          <div className="text-[10px] font-semibold tracking-[0.14em] text-muted uppercase">Trạng thái ghế</div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            {statusLegend.map((item) => (
              <div key={item.label} className="flex items-center gap-2 text-[11px] text-muted">
                <span className={`size-3 shrink-0 rounded ${item.className}`} />
                {item.label}
              </div>
            ))}
          </div>
        </div>

        <div className="border-t border-border pt-4">
          <div className="text-[10px] font-semibold tracking-[0.14em] text-muted uppercase">Hạng ghế</div>
          <div className="mt-3 flex flex-col gap-2">
            {tierLegend.map((item) => {
              const price = pricesByTier.get(item.tier)
              return (
                <div key={item.tier} className="grid min-h-9 grid-cols-[12px_minmax(0,1fr)_auto] items-center gap-2 rounded-lg border border-border bg-surface-2/60 px-2.5">
                  <span className={`size-2.5 rounded-full ${item.className}`} />
                  <strong className="text-xs">{item.label}</strong>
                  <span className="text-[10px] text-muted">{price === undefined ? '—' : formatCurrency(price)}</span>
                </div>
              )
            })}
          </div>
          <p className="mt-3 text-[10px] leading-4 text-muted">
            Màu viền thể hiện hạng vé; màu nền thể hiện trạng thái.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}

const SeatGuide = memoComponent(SeatGuideComponent)

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
  const [tierFilter, setTierFilter] = useState<TierFilter>('all')
  const [layout, setLayout] = useState<SeatLayout | null>(null)
  const [selectedSectionId, setSelectedSectionId] = useState<string | null>(null)
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null)

  const selectedRef = useRef<string[]>([])
  selectedRef.current = selected
  const continuingToCheckoutRef = useRef(false)

  useEffect(() => {
    return () => {
      if (continuingToCheckoutRef.current) return
      if (!state?.showtimeId || selectedRef.current.length === 0) return
      unholdSeats(state.showtimeId, selectedRef.current).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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

    getSeatLayout(state.showtimeId).then((data) => {
      if (!cancelled) {
        setLayout(data)
        if (!data?.sections?.length) {
          setSelectedSectionId(null)
          setSelectedBlockId(null)
        }
      }
    })

    return () => {
      cancelled = true
    }
  }, [state?.showtimeId, toast])

  const stadiumMap = useMemo(
    () => buildStadiumMap(seats, state?.stadiumId ?? ''),
    [seats, state?.stadiumId],
  )
  const selectedSet = useMemoizedSet(selected)
  const seatsByCode = useMemoizedKeyMap(seats, 'code')
  const selectedSeats = useMemo(
    () => seats.filter((seat) => selectedSet.has(seat.code)),
    [seats, selectedSet],
  )
  const total = useMemo(
    () => selectedSeats.reduce((sum, seat) => sum + seat.price, 0),
    [selectedSeats],
  )
  const selectedSection = useMemo(
    () => layout?.sections.find((section) => section.id === selectedSectionId) ?? null,
    [layout, selectedSectionId],
  )
  const selectedBlock = useMemo(
    () => selectedSection?.blocks.find((block) => block.id === selectedBlockId) ?? null,
    [selectedBlockId, selectedSection],
  )
  const blockSeatCodes = useMemo<ReadonlySet<string> | null>(
    () => selectedBlock ? new Set(selectedBlock.seatCodes) : null,
    [selectedBlock],
  )
  const remainingForCodes = useCallback((seatCodes: string[]) => seatCodes.reduce((count, code) => {
    const seat = seatsByCode.get(code)
    return count + (seat?.status === 'available' && !selectedSet.has(code) ? 1 : 0)
  }, 0), [seatsByCode, selectedSet])
  const availableCounts = useMemo(() => seats.reduce(
    (counts, seat) => {
      if (seat.status === 'available' && !selectedSet.has(seat.code)) {
        counts.all += 1
        counts[seat.tier] += 1
      }
      return counts
    },
    { all: 0, standard: 0, premium: 0, vip: 0 } as Record<TierFilter, number>,
  ), [seats, selectedSet])

  const toggleSeat = useCallback((code: string) => {
    const showtimeId = state?.showtimeId
    if (!showtimeId) return

    if (selectedSet.has(code)) {
      setSelected((current) => current.filter((seatCode) => seatCode !== code))
      unholdSeats(showtimeId, [code]).catch(() => {})
      return
    }

    if (selected.length >= MAX_SEATS) {
      toast({
        title: `Tối đa ${MAX_SEATS} ghế`,
        description: 'Vui lòng bỏ bớt ghế đang chọn trước khi chọn thêm.',
        variant: 'error',
      })
      return
    }

    if (!stadiumMap && tierFilter !== 'all') {
      const seat = seatsByCode.get(code)
      if (seat && seat.tier !== tierFilter) return
    }
    if (!stadiumMap && blockSeatCodes && !blockSeatCodes.has(code)) return

    setSelected((current) => [...current, code])
    holdSeats(showtimeId, [code]).catch((err: unknown) => {
      setSelected((current) => current.filter((seatCode) => seatCode !== code))
      toast({
        title: 'Không thể giữ ghế',
        description: getErrorMessage(err, 'Ghế này vừa được người khác chọn. Vui lòng chọn ghế khác.'),
        variant: 'error',
      })
      getSeatMap(showtimeId).then((data) => setSeats(data.seats)).catch(() => {})
    })
  }, [blockSeatCodes, seatsByCode, selected.length, selectedSet, stadiumMap, state?.showtimeId, tierFilter, toast])

  const clearSelection = useCallback(() => {
    if (!state?.showtimeId || selected.length === 0) return
    const codes = [...selected]
    setSelected([])
    unholdSeats(state.showtimeId, codes).catch(() => {})
  }, [selected, state?.showtimeId])

  const handleContinue = useCallback(() => {
    if (!matchId || !state?.showtimeId) return
    continuingToCheckoutRef.current = true
    const checkoutState: CheckoutState = {
      matchId,
      matchLabel: state.matchLabel,
      showtimeId: state.showtimeId,
      seatCodes: selected,
      amount: total,
      currency: 'VND',
    }
    navigate('/checkout', { state: checkoutState })
  }, [matchId, navigate, selected, state?.matchLabel, state?.showtimeId, total])

  if (!matchId || !state?.showtimeId || !state.startTime || new Date(state.startTime).getTime() <= Date.now()) {
    return <Navigate to={matchId ? `/matches/${matchId}` : '/'} replace />
  }

  if (failed) return <Navigate to={`/matches/${matchId}`} replace />

  return (
    <section className="mx-auto max-w-[1180px] px-4 py-8 sm:px-6 sm:py-12">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-6"
      >
        <div className="text-center">
          <h1 className="text-2xl font-bold sm:text-3xl">Chọn ghế</h1>
          <p className="text-muted">{state.matchLabel} — tối đa {MAX_SEATS} ghế mỗi lượt đặt</p>
        </div>

        <div className="grid items-start gap-6 lg:grid-cols-[280px_minmax(0,1fr)]">
          <div className="flex min-w-0 flex-col gap-3 lg:sticky lg:top-24">
            <SeatGuide seats={seats} />

            <Card>
              <CardContent className="flex flex-col gap-4 p-5">
                <div>
                  <div className="text-xs font-semibold tracking-wider text-muted uppercase">Đơn đặt ghế</div>
                  <div className="mt-3 flex items-center justify-between gap-3">
                    <span className="text-sm font-semibold">Ghế đã chọn</span>
                    <span className="text-xs text-muted">{selectedSeats.length} ghế</span>
                  </div>
                  <div className="mt-3 flex min-h-14 max-h-28 flex-wrap content-start gap-1.5 overflow-y-auto rounded-lg border border-dashed border-border bg-background/35 p-2.5">
                    {selectedSeats.length > 0 ? selectedSeats.map((seat) => (
                      <span key={seat.code} className="inline-flex h-6 items-center rounded-md border border-primary/35 bg-primary/10 px-2 text-[10px] font-bold text-primary">
                        {seat.code}
                      </span>
                    )) : (
                      <span className="m-auto text-[10px] text-muted">Chưa chọn ghế nào</span>
                    )}
                  </div>
                </div>
                <div className="flex items-end justify-between gap-3 border-t border-border pt-4">
                  <div className="text-xs text-muted">Tổng thanh toán</div>
                  <div className="text-2xl font-bold">{formatCurrency(total)}</div>
                </div>
                <Button
                  variant="gradient"
                  size="lg"
                  className="w-full"
                  disabled={selectedSeats.length === 0}
                  onClick={handleContinue}
                >
                  Tiếp tục thanh toán
                </Button>
              </CardContent>
            </Card>
          </div>

          <div className="min-w-0">
          <div className={stadiumMap ? '' : 'overflow-x-auto rounded-xl border border-border bg-surface p-6 shadow-card'}>
            {loading ? (
              <div className="flex justify-center py-12">
                <Loader2 className="size-8 animate-spin text-accent" />
              </div>
            ) : stadiumMap ? (
              <StadiumSeatMap
                stadium={stadiumMap.stadium}
                zones={stadiumMap.zones}
                selected={selected}
                onToggle={toggleSeat}
                onClearSelection={clearSelection}
              />
            ) : (
              <div className="flex flex-col gap-4">
                {(layout?.sections.length ?? 0) > 0 ? (
                  <div className="flex flex-col gap-3">
                    <div className="flex flex-wrap items-center gap-2">
                      {layout!.sections.map((section) => (
                        <Button
                          key={section.id}
                          type="button"
                          size="sm"
                          variant={selectedSectionId === section.id ? 'default' : 'outline'}
                          onClick={() => {
                            setSelectedSectionId(section.id)
                            setSelectedBlockId(null)
                          }}
                        >
                          {section.name}
                          <Badge variant={selectedSectionId === section.id ? 'accent' : 'outline'} className="ml-2">
                            {remainingForCodes(section.blocks.flatMap((block) => block.seatCodes))}
                          </Badge>
                        </Button>
                      ))}
                      <span className="text-xs text-muted">Chọn khu</span>
                    </div>
                    {selectedSection ? (
                      <div className="flex flex-wrap items-center gap-2">
                        {selectedSection.blocks.map((block) => (
                          <Button
                            key={block.id}
                            type="button"
                            size="sm"
                            variant={selectedBlockId === block.id ? 'default' : 'outline'}
                            onClick={() => setSelectedBlockId(block.id)}
                          >
                            {block.name}
                            <Badge variant={selectedBlockId === block.id ? 'accent' : 'outline'} className="ml-2">
                              {remainingForCodes(block.seatCodes)}
                            </Badge>
                          </Button>
                        ))}
                        <span className="text-xs text-muted">Chọn block</span>
                      </div>
                    ) : null}
                  </div>
                ) : null}

                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex flex-wrap gap-2">
                    {(['all', 'standard', 'premium', 'vip'] as TierFilter[]).map((tier) => (
                      <Button
                        key={tier}
                        type="button"
                        size="sm"
                        variant={tierFilter === tier ? 'default' : 'outline'}
                        onClick={() => setTierFilter(tier)}
                      >
                        {getTierLabel(tier)}
                        <Badge variant={tierFilter === tier ? 'accent' : 'outline'} className="ml-2">
                          {availableCounts[tier]}
                        </Badge>
                      </Button>
                    ))}
                  </div>
                  <span className="text-xs text-muted">Lọc theo tier</span>
                </div>

                <SeatMap
                  seats={seats}
                  selected={selected}
                  onToggle={toggleSeat}
                  tierFilter={tierFilter}
                  blockSeatCodes={blockSeatCodes}
                />
              </div>
            )}
          </div>
          </div>
        </div>
      </motion.div>
    </section>
  )
}
