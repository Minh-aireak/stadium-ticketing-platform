import { api } from '@/lib/api'
import type { Match, MatchListResponse } from '@/features/matches/types'
import type { AddShowtimeRequest, CreateMatchRequest, CreateMatchResponse, StadiumSummary } from './types'

// All endpoints below are ADMIN-only on match-catalog-service (see MatchController's
// requireAdminRole()) — the gateway forwards the bearer token, the service checks the
// "role" JWT claim itself. A non-admin caller gets a 403 ProblemDetail.

export async function createMatch(payload: CreateMatchRequest): Promise<CreateMatchResponse> {
  const { data } = await api.post<CreateMatchResponse>('/matches', payload)
  return data
}

export async function addShowtime(matchId: string, payload: AddShowtimeRequest): Promise<void> {
  await api.post(`/matches/${matchId}/showtimes`, payload)
}

export async function listStadiums(): Promise<StadiumSummary[]> {
  const { data } = await api.get<StadiumSummary[]>('/matches/stadiums')
  return data
}

export async function publishMatch(matchId: string): Promise<void> {
  await api.put(`/matches/${matchId}/publish`)
}

export async function cancelMatch(matchId: string, reason: string): Promise<void> {
  await api.put(`/matches/${matchId}/cancel`, { reason })
}

export async function completeMatch(matchId: string): Promise<void> {
  await api.put(`/matches/${matchId}/complete`)
}

// Reuses the public catalog list — it's server-filtered to PUBLISHED matches only, which is
// exactly the "live/active matches" set an admin cancels or completes. Freshly created DRAFT
// matches aren't listable here (or via GET /matches/{id}) by design on the backend side; the
// create-match flow carries the new matchId forward client-side until it's published.
export async function listActiveMatches(params: { q?: string; page?: number; size?: number } = {}): Promise<MatchListResponse> {
  const { data } = await api.get<MatchListResponse>('/matches', { params })
  return data
}

export type { Match }
