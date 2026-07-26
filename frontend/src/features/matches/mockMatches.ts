import type { Match } from './types'

// TODO: remove once GET /api/matches (match-catalog-service) is wired up.
export const mockFeaturedMatches: Match[] = [
  {
    id: 'm1',
    homeTeam: 'Song Han FC',
    awayTeam: 'Thanh Long United',
    homeTeamInitials: 'SH',
    awayTeamInitials: 'TL',
    competition: 'V.League 1',
    stadium: 'Sân vận động Thống Nhất',
    kickoffAt: new Date(Date.now() + 1000 * 60 * 60 * 26).toISOString(),
    fromPrice: 150000,
    currency: 'VND',
    ticketsRemaining: 1240,
    status: 'on_sale',
  },
  {
    id: 'm2',
    homeTeam: 'Cang Sai Gon',
    awayTeam: 'Hai Dang City',
    homeTeamInitials: 'CS',
    awayTeamInitials: 'HD',
    competition: 'Cúp Quốc Gia',
    stadium: 'Sân vận động Mỹ Đình',
    kickoffAt: new Date(Date.now() + 1000 * 60 * 60 * 3).toISOString(),
    fromPrice: 250000,
    currency: 'VND',
    ticketsRemaining: 68,
    status: 'few_left',
  },
  {
    id: 'm3',
    homeTeam: 'Bien Xanh FC',
    awayTeam: 'Nui Rung',
    homeTeamInitials: 'BX',
    awayTeamInitials: 'NR',
    competition: 'V.League 1',
    stadium: 'Sân vận động Lạch Tray',
    kickoffAt: new Date(Date.now() + 1000 * 60 * 60 * 72).toISOString(),
    fromPrice: 120000,
    currency: 'VND',
    ticketsRemaining: 0,
    status: 'sold_out',
  },
  {
    id: 'm4',
    homeTeam: 'Hoang Kim SC',
    awayTeam: 'Rong Vang',
    homeTeamInitials: 'HK',
    awayTeamInitials: 'RV',
    competition: 'AFC Champions League',
    stadium: 'Sân vận động Cần Thơ',
    kickoffAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 6).toISOString(),
    fromPrice: 300000,
    currency: 'VND',
    ticketsRemaining: 2100,
    status: 'upcoming',
  },
]

export function getMockMatchById(id: string): Match | undefined {
  return mockFeaturedMatches.find((m) => m.id === id)
}
