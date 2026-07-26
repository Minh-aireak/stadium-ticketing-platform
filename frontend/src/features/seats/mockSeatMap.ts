import type { Seat, SeatStatus } from './types'

// TODO: replace with GET /api/v1/inventory/{showtimeId}/seats once ticket-inventory-service
// exposes a read endpoint — today it only has reserve/release/confirm (internal, saga-only).
const ROW_TIERS: Record<string, Seat['tier']> = {
  A: 'vip',
  B: 'vip',
  C: 'premium',
  D: 'premium',
  E: 'standard',
  F: 'standard',
}

const TIER_MULTIPLIER: Record<Seat['tier'], number> = {
  vip: 2.2,
  premium: 1.5,
  standard: 1,
}

const SEATS_PER_ROW = 10

function hashSeed(input: string): number {
  let h = 0
  for (let i = 0; i < input.length; i++) {
    h = (h * 31 + input.charCodeAt(i)) >>> 0
  }
  return h
}

function statusFor(seed: number, code: string): SeatStatus {
  const bucket = (seed + hashSeed(code)) % 100
  if (bucket < 12) return 'sold'
  if (bucket < 20) return 'held'
  return 'available'
}

export function generateMockSeatMap(showtimeId: string, basePrice: number): Seat[] {
  const seed = hashSeed(showtimeId)
  const seats: Seat[] = []

  for (const row of Object.keys(ROW_TIERS)) {
    const tier = ROW_TIERS[row]
    for (let n = 1; n <= SEATS_PER_ROW; n++) {
      const code = `${row}${n}`
      seats.push({
        code,
        row,
        number: n,
        status: statusFor(seed, code),
        tier,
        price: Math.round((basePrice * TIER_MULTIPLIER[tier]) / 1000) * 1000,
      })
    }
  }

  return seats
}
