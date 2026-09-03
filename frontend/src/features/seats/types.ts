export type SeatStatus = 'available' | 'held' | 'sold'

export interface Seat {
  code: string
  row: string
  number: number
  status: SeatStatus
  price: number
  tier: 'standard' | 'premium' | 'vip'
  /**
   * True for a 'held' seat whose hold belongs to this customer rather than another shopper.
   * Every hold used to look alike here, so reloading seat selection turned the customer's own
   * seats into someone else's for the rest of the hold TTL.
   */
  heldByYou: boolean
}

// Layout topology for the Section/Block picker (Phương án B). Served by a separate
// GET /inventory/{showtimeId}/layout endpoint so the realtime /seats DTO stays clean.
export interface SeatLayoutBlock {
  id: string
  name: string
  seatCodes: string[]
}

export interface SeatLayoutSection {
  id: string
  name: string
  blocks: SeatLayoutBlock[]
}

export interface SeatLayout {
  showtimeId: string
  sections: SeatLayoutSection[]
}