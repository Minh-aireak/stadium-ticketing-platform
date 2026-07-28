import type { Match, Showtime } from './types'

// Below this many total remaining seats across all showtimes, the card badge switches
// from "on sale" to "selling fast" — mirrors what the old mock data used as a rough cutoff.
const FEW_LEFT_THRESHOLD = 100

export function teamInitials(name: string): string {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => word[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()
  return initials || '?'
}

/** The next showtime yet to start, or the most recent past one if none remain. */
export function nearestShowtime(match: Match): Showtime | undefined {
  if (match.showtimes.length === 0) return undefined
  const now = Date.now()
  const byStartTimeAsc = [...match.showtimes].sort(
    (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime(),
  )
  return byStartTimeAsc.find((s) => new Date(s.startTime).getTime() >= now) ?? byStartTimeAsc.at(-1)
}

export function ticketsRemaining(match: Match): number {
  return match.showtimes.reduce((sum, s) => sum + s.availableSeats, 0)
}

export type CatalogStatus = 'sold_out' | 'few_left' | 'on_sale'

export function catalogStatus(match: Match): CatalogStatus {
  if (match.status !== 'PUBLISHED' || ticketsRemaining(match) <= 0) return 'sold_out'
  if (ticketsRemaining(match) < FEW_LEFT_THRESHOLD) return 'few_left'
  return 'on_sale'
}
