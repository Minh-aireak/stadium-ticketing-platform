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

export interface BookingSummary {
  bookingId: string
  showtimeId: string
  seatCodes: string[]
  amount: number
  currency: string
  status: BookingStatus
  createdAt: string // ISO timestamp
}

export interface BookingListResponse {
  items: BookingSummary[]
  totalElements: number
  page: number
  size: number
}
