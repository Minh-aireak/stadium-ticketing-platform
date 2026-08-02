// Mirrors match-catalog-service's MatchController admin-only request/response DTOs
// (POST /matches, POST /matches/{id}/showtimes, PUT .../publish|cancel|complete).
export interface CreateMatchRequest {
  homeTeam: string
  awayTeam: string
  competition: string
}

export interface CreateMatchResponse {
  matchId: string
}

export interface AddShowtimeRequest {
  startTime: string // ISO timestamp
  stadiumId: string
  basePrice: number
  currency: string // 3-letter uppercase, e.g. "VND"
}

export interface StadiumSummary {
  id: string
  name: string
  totalSeats: number
  levels: number
  design: 'OVAL' | 'COMPACT' | 'MULTI_TIER'
}

export interface CancelMatchRequest {
  reason: string
}
