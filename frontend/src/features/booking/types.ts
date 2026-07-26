// Mirrors booking-service's BookingController DTOs.
export type BookingStatus = 'DRAFT' | 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED'

export interface CreateBookingRequest {
  customerId: string
  showtimeId: string
  seatCodes: string[]
  amount: number
  currency: string
}

export interface CreateBookingResponse {
  bookingId: string
  status: BookingStatus
}

export interface BookingStatusResponse {
  bookingId: string
  status: BookingStatus
}
