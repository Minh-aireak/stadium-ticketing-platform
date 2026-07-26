// TODO: replace with a real "list my bookings" endpoint once booking-service exposes one —
// today BookingController only supports GET /bookings/{id} (single lookup by id), no
// list-by-customer query.
export interface MockTicket {
  bookingId: string
  matchLabel: string
  seatCodes: string[]
  purchasedAt: string
  status: 'CONFIRMED' | 'PENDING_PAYMENT'
}

export const mockTickets: MockTicket[] = [
  {
    bookingId: 'bk-8f21',
    matchLabel: 'Song Han FC vs Thanh Long United',
    seatCodes: ['B4', 'B5'],
    purchasedAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 3).toISOString(),
    status: 'CONFIRMED',
  },
  {
    bookingId: 'bk-3a90',
    matchLabel: 'Cang Sai Gon vs Hai Dang City',
    seatCodes: ['E12'],
    purchasedAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 10).toISOString(),
    status: 'CONFIRMED',
  },
]
