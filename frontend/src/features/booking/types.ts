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
  /**
   * The charge booking-service computed server-side from each seat's tier — never the
   * placeholder amount the client posted with the booking. payment-service reads this same
   * field off this same endpoint to check the amount it was asked to charge.
   */
  amount: number
  currency: string
}

/**
 * What PUT /bookings/{id}/cancel answers with: the booking after the cancel. `seatCodes` here are
 * the seats it still holds — unlike BookingSummary, where they are every seat it was made for.
 */
export interface CancelBookingResponse {
  bookingId: string
  status: BookingStatus
  amount: number
  currency: string
  seatCodes: string[]
  cancelledSeatCodes: string[]
  refundedAmount: number
}

export interface BookingSummary {
  bookingId: string
  showtimeId: string
  /** Every seat the booking was made for, including any since cancelled. */
  seatCodes: string[]
  amount: number
  currency: string
  status: BookingStatus
  createdAt: string // ISO timestamp
  /** Seats the booking no longer holds — all of them once it is CANCELLED. */
  cancelledSeatCodes: string[]
  /** Refunds requested so far for cancelled seats, in `currency`. */
  refundedAmount: number
}

export interface BookingListResponse {
  items: BookingSummary[]
  totalElements: number
  page: number
  size: number
}
