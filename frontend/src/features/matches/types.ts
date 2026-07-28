// Shape returned by match-catalog-service via api-gateway (GET /api/v1/matches, GET /api/v1/matches/{id}).
// No price/tier data exists in the backend yet — do not fabricate fromPrice/currency here.
export type MatchStatus = 'DRAFT' | 'PUBLISHED' | 'COMPLETED' | 'CANCELLED'

export interface Showtime {
  showtimeId: string
  startTime: string // ISO timestamp
  venueId: string
  totalSeats: number
  availableSeats: number
}

export interface Match {
  matchId: string
  homeTeam: string
  awayTeam: string
  competition: string
  status: MatchStatus
  createdAt: string // ISO timestamp
  showtimes: Showtime[]
}

export interface MatchListResponse {
  items: Match[]
  totalElements: number
  page: number
  size: number
}
