import { api } from '@/lib/api'
import type { Seat } from './types'

export interface SeatMapResponse {
  showtimeId: string
  seats: Seat[]
}

// GET /api/v1/inventory/{showtimeId}/seats — requires auth (see api-gateway's
// RateLimitPolicy.READ_AUTHENTICATED for /api/v1/inventory/**). Seat status folds in live
// Redis hold state, not just the Postgres snapshot — see SeatMapQueryService on the backend.
export async function getSeatMap(showtimeId: string): Promise<SeatMapResponse> {
  const { data } = await api.get<SeatMapResponse>(`/inventory/${showtimeId}/seats`)
  return data
}
