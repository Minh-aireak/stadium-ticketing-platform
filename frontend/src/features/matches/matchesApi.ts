import { api } from '@/lib/api'
import type { Match, MatchListResponse } from './types'

export interface ListMatchesParams {
  q?: string
  page?: number
  size?: number
}

// Public catalog browse/search — no auth required (see match-catalog-service's
// method-scoped jwt.excluded-paths for GET /matches and GET /matches/{id}).
export async function listMatches(params: ListMatchesParams = {}): Promise<MatchListResponse> {
  const { data } = await api.get<MatchListResponse>('/matches', { params })
  return data
}

export async function getMatch(matchId: string): Promise<Match> {
  const { data } = await api.get<Match>(`/matches/${matchId}`)
  return data
}
