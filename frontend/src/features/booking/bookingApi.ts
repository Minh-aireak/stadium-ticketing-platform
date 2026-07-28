import { api } from '@/lib/api'
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
  const { data } = await api.post<CreateBookingResponse>('/bookings', payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
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
