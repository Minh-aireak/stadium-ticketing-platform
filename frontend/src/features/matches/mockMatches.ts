// Deferred: only SeatSelectionPage still depends on this. Home/MatchDetail now use the real
// GET /api/v1/matches API (see matchesApi.ts, types.ts) — this file is intentionally decoupled
// from that real Match type since Seat Selection needs its own pricing/inventory design
// (ticket-inventory-service has no seat-map read endpoint or price/tier concept yet) before it
// can be wired up for real. Remove this file once that screen is tackled.
export interface MockMatchForSeats {
  id: string
  homeTeam: string
  awayTeam: string
  fromPrice: number
}

const mockMatches: MockMatchForSeats[] = [
  { id: 'm1', homeTeam: 'Song Han FC', awayTeam: 'Thanh Long United', fromPrice: 150000 },
  { id: 'm2', homeTeam: 'Cang Sai Gon', awayTeam: 'Hai Dang City', fromPrice: 250000 },
  { id: 'm3', homeTeam: 'Bien Xanh FC', awayTeam: 'Nui Rung', fromPrice: 120000 },
  { id: 'm4', homeTeam: 'Hoang Kim SC', awayTeam: 'Rong Vang', fromPrice: 300000 },
]

export function getMockMatchById(id: string): MockMatchForSeats | undefined {
  return mockMatches.find((m) => m.id === id) ?? { id, homeTeam: 'Đội nhà', awayTeam: 'Đội khách', fromPrice: 150000 }
}
