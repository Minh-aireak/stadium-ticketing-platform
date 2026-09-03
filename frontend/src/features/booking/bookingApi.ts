import { api, ensureAccessToken } from '@/lib/api'
import type {
  BookingListResponse,
  BookingStatusResponse,
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
