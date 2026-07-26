export type SeatStatus = 'available' | 'held' | 'sold'

export interface Seat {
  code: string
  row: string
  number: number
  status: SeatStatus
  price: number
  tier: 'standard' | 'premium' | 'vip'
}
