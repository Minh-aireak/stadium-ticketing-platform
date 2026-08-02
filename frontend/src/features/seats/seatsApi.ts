import { api } from '@/lib/api'
import type { Seat, SeatLayout } from './types'

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

// GET /api/v1/inventory/{showtimeId}/layout — best-effort topology for the Section/Block
// picker (Phương án B). The backend may not have deployed this endpoint yet, so callers
// must treat 404/5xx as "no layout available" and fall back to the plain seat map.
export async function getSeatLayout(showtimeId: string): Promise<SeatLayout | null> {
  try {
    const { data } = await api.get<SeatLayout>(`/inventory/${showtimeId}/layout`)
    return data
  } catch {
    return null
  }
}

export interface HoldSeatsResponse {
  totalPrice: number
}

// POST /api/v1/inventory/{showtimeId}/hold — places a short TTL hold on the given seats for the
// authenticated caller, as soon as they're selected (well before a booking exists). Booking
// creation later confirms this same hold over instead of re-acquiring it — see
// ticket-inventory-service's SeatHoldPort#confirmHold.
export async function holdSeats(showtimeId: string, seatCodes: string[]): Promise<HoldSeatsResponse> {
  const { data } = await api.post<HoldSeatsResponse>(`/inventory/${showtimeId}/hold`, { seatCodes })
  return data
}

// DELETE /api/v1/inventory/{showtimeId}/hold — releases the caller's own hold (deselect, or
// leaving seat selection without checking out). Best-effort: an unreleased hold self-expires via
// Redis TTL anyway, so callers should swallow failures here rather than surface them.
export async function unholdSeats(showtimeId: string, seatCodes: string[]): Promise<void> {
  // Comma-joined single query value, same convention as booking-service's release call to this
  // same seatCodes param — Spring splits a single delimited value into the bound List<String>.
  await api.delete(`/inventory/${showtimeId}/hold`, { params: { seatCodes: seatCodes.join(',') } })
}
