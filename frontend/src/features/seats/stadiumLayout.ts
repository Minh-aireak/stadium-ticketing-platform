import type { Seat } from './types'

export type StandId = 'north' | 'east' | 'south' | 'west'
export type StadiumDesign = 'oval' | 'compact' | 'multi-tier'

export interface StadiumLevel {
  number: number
  name: string
  innerRadius: number
  outerRadius: number
  captionRadius: number
}

export interface StadiumRowDefinition {
  label: string
  level: number
  capacity: number
  radius: number
}

export interface StadiumDefinition {
  id: string
  name: string
  totalSeats: number
  design: StadiumDesign
  zoneHalfSpan: number
  outlineRadius: number
  focusRadius: number
  zoomScale: number
  pitchWidth: number
  levels: StadiumLevel[]
  rows: StadiumRowDefinition[]
}

export interface StadiumRowSeat {
  seat: Seat | null
  displayNumber: number
}

export interface StadiumRow extends StadiumRowDefinition {
  id: string
  seats: StadiumRowSeat[]
}

export interface StadiumZone {
  id: StandId
  name: string
  startAngle: number
  endAngle: number
  seats: Seat[]
  rows: StadiumRow[]
}

export interface StadiumMap {
  stadium: StadiumDefinition
  zones: StadiumZone[]
}

export const STADIUMS: StadiumDefinition[] = [
  {
    id: 'my-dinh',
    name: 'Sân vận động Quốc gia Mỹ Đình',
    totalSeats: 432,
    design: 'oval',
    zoneHalfSpan: 42,
    outlineRadius: 454,
    focusRadius: 330,
    zoomScale: 1.7,
    pitchWidth: 360,
    levels: [
      { number: 1, name: 'Tầng 1', innerRadius: 236, outerRadius: 314, captionRadius: 225 },
      { number: 2, name: 'Tầng 2', innerRadius: 352, outerRadius: 430, captionRadius: 443 },
    ],
    rows: [
      { label: 'A', level: 1, capacity: 14, radius: 250 },
      { label: 'B', level: 1, capacity: 15, radius: 274 },
      { label: 'C', level: 1, capacity: 16, radius: 298 },
      { label: 'D', level: 2, capacity: 20, radius: 366 },
      { label: 'E', level: 2, capacity: 21, radius: 390 },
      { label: 'F', level: 2, capacity: 22, radius: 414 },
    ],
  },
  {
    id: 'thong-nhat',
    name: 'Sân vận động Thống Nhất',
    totalSeats: 320,
    design: 'compact',
    zoneHalfSpan: 38,
    outlineRadius: 426,
    focusRadius: 330,
    zoomScale: 1.78,
    pitchWidth: 340,
    levels: [
      { number: 1, name: 'Khán đài chính', innerRadius: 252, outerRadius: 408, captionRadius: 421 },
    ],
    rows: [
      { label: 'A', level: 1, capacity: 14, radius: 270 },
      { label: 'B', level: 1, capacity: 15, radius: 300 },
      { label: 'C', level: 1, capacity: 16, radius: 330 },
      { label: 'D', level: 1, capacity: 17, radius: 360 },
      { label: 'E', level: 1, capacity: 18, radius: 390 },
    ],
  },
  {
    id: 'hang-day',
    name: 'Sân vận động Hàng Đẫy',
    totalSeats: 540,
    design: 'multi-tier',
    zoneHalfSpan: 44,
    outlineRadius: 474,
    focusRadius: 342,
    zoomScale: 1.52,
    pitchWidth: 350,
    levels: [
      { number: 1, name: 'Tầng 1', innerRadius: 222, outerRadius: 286, captionRadius: 211 },
      { number: 2, name: 'Tầng 2', innerRadius: 310, outerRadius: 374, captionRadius: 298 },
      { number: 3, name: 'Tầng 3', innerRadius: 398, outerRadius: 462, captionRadius: 475 },
    ],
    rows: [
      { label: 'A', level: 1, capacity: 10, radius: 234 },
      { label: 'B', level: 1, capacity: 11, radius: 254 },
      { label: 'C', level: 1, capacity: 12, radius: 274 },
      { label: 'D', level: 2, capacity: 14, radius: 322 },
      { label: 'E', level: 2, capacity: 15, radius: 342 },
      { label: 'F', level: 2, capacity: 16, radius: 362 },
      { label: 'G', level: 3, capacity: 18, radius: 410 },
      { label: 'H', level: 3, capacity: 19, radius: 430 },
      { label: 'I', level: 3, capacity: 20, radius: 450 },
    ],
  },
]

const STAND_META: { id: StandId; name: string; midpoint: number }[] = [
  { id: 'north', name: 'Khán đài Bắc', midpoint: 0 },
  { id: 'east', name: 'Khán đài Đông', midpoint: 90 },
  { id: 'south', name: 'Khán đài Nam', midpoint: 180 },
  { id: 'west', name: 'Khán đài Tây', midpoint: 270 },
]

export function getStadiumDefinition(stadiumId: string) {
  return STADIUMS.find((stadium) => stadium.id === stadiumId) ?? null
}

function distributeAcrossStands(rowSeats: Seat[], rowIndex: number) {
  const baseSize = Math.floor(rowSeats.length / STAND_META.length)
  const remainder = rowSeats.length % STAND_META.length
  const extraStart = rowIndex % STAND_META.length
  const result: Seat[][] = STAND_META.map(() => [])
  let cursor = 0

  for (let standIndex = 0; standIndex < STAND_META.length; standIndex += 1) {
    const extraDistance = (standIndex - extraStart + STAND_META.length) % STAND_META.length
    const size = baseSize + (extraDistance < remainder ? 1 : 0)
    result[standIndex] = rowSeats.slice(cursor, cursor + size)
    cursor += size
  }
  return result
}

function fillRowSlots(definition: StadiumRowDefinition, seats: Seat[]): StadiumRowSeat[] {
  const slots = Array.from({ length: definition.capacity }, (_, slotIndex): StadiumRowSeat => ({
    seat: null,
    displayNumber: slotIndex + 1,
  }))

  seats.slice(0, definition.capacity).forEach((seat, seatIndex, rowSeats) => {
    const slotIndex = rowSeats.length === 1
      ? Math.floor((definition.capacity - 1) / 2)
      : Math.round(seatIndex * (definition.capacity - 1) / (rowSeats.length - 1))
    slots[slotIndex] = { seat, displayNumber: slotIndex + 1 }
  })
  return slots
}

export function buildStadiumMap(seats: Seat[], stadiumId: string): StadiumMap | null {
  const stadium = getStadiumDefinition(stadiumId)
  if (!stadium) return null

  const supportedRows = new Set(stadium.rows.map((row) => row.label))
  if (seats.some((seat) => !supportedRows.has(seat.row))) return null

  const zones: StadiumZone[] = STAND_META.map((stand) => ({
    id: stand.id,
    name: stand.name,
    startAngle: stand.midpoint - stadium.zoneHalfSpan,
    endAngle: stand.midpoint + stadium.zoneHalfSpan,
    seats: [],
    rows: [],
  }))

  stadium.rows.forEach((rowDefinition, rowIndex) => {
    const backendRow = seats
      .filter((seat) => seat.row === rowDefinition.label)
      .sort((a, b) => a.number - b.number)
    const distributed = distributeAcrossStands(backendRow, rowIndex)

    zones.forEach((zone, standIndex) => {
      const standSeats = distributed[standIndex]
      zone.seats.push(...standSeats)
      zone.rows.push({
        ...rowDefinition,
        id: `level-${rowDefinition.level}-row-${rowDefinition.label}`,
        seats: fillRowSlots(rowDefinition, standSeats),
      })
    })
  })

  return { stadium, zones }
}
