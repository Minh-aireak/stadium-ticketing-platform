// Shape returned by match-catalog-service via api-gateway (GET /api/matches).
export interface Match {
  id: string
  homeTeam: string
  awayTeam: string
  // TODO: replace with real crest image URL once match-catalog-service exposes one.
  homeTeamInitials: string
  awayTeamInitials: string
  competition: string
  stadium: string
  kickoffAt: string // ISO timestamp
  fromPrice: number
  currency: 'VND'
  ticketsRemaining: number
  status: 'on_sale' | 'few_left' | 'sold_out' | 'upcoming'
}
