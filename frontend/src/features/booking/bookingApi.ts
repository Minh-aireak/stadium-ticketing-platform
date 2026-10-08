import { api, ensureAccessToken } from '@/lib/api'
import type {
  BookingListResponse,
  BookingStatusResponse,
  CancelBookingResponse,
  CreateBookingRequest,
  CreateBookingResponse,
} from './types'

// Idempotency-Key lets a retried click (flaky network, double submit) land on the same
// booking instead of creating a duplicate — booking-service dedupes on this header.
export async function createBooking(
  payload: CreateBookingRequest,
  idempotencyKey: string,
): Promise<CreateBookingResponse> {
  // Checkout is a protected transition and must never emit an anonymous first attempt. The
  // in-memory access token can be empty immediately after a page/bundle reload, while the
  // HttpOnly refresh session is still valid. Restore it before POST instead of relying on a
  // rejected 401 round-trip (which also complicates the Idempotency-Key CORS preflight).
  // ensureAccessToken, not a bare refreshAccessToken: a refresh that fails here means the
  // session really is over, and that has to reach AuthProvider rather than surfacing as a
  // checkout error the customer is invited to retry.
  const accessToken = await ensureAccessToken()
  const { data } = await api.post<CreateBookingResponse>('/bookings', payload, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Idempotency-Key': idempotencyKey,
    },
  })
  return data
}

export async function getBooking(bookingId: string): Promise<BookingStatusResponse> {
  const { data } = await api.get<BookingStatusResponse>(`/bookings/${bookingId}`)
  return data
}

export interface ListBookingsParams {
  page?: number
  size?: number
}

// "My tickets" — always scoped server-side to the JWT-authenticated caller.
export async function listMyBookings(params: ListBookingsParams = {}): Promise<BookingListResponse> {
  const { data } = await api.get<BookingListResponse>('/bookings', { params })
  return data
}

// FR-21: the customer cancels seats of their own booking. No `seatCodes` means every seat it still
// holds — the only form an unpaid booking accepts. A paid booking can name single seats until 24
// hours before kickoff; each is refunded at its own price. PUT because a repeat is a no-op
// server-side (seats already cancelled are answered as-is), so a retried click cannot do anything
// the first one did not. A cancel that collides with another one still running is answered 409.
export async function cancelBooking(bookingId: string, seatCodes?: string[]): Promise<CancelBookingResponse> {
  const body = seatCodes && seatCodes.length > 0 ? { seatCodes } : undefined
  const { data } = await api.put<CancelBookingResponse>(`/bookings/${bookingId}/cancel`, body)
  return data
}
