import type { Match, Showtime } from './types'

// Below this many total remaining seats across all showtimes, the card badge switches
// from "on sale" to "selling fast" — mirrors what the old mock data used as a rough cutoff.
const FEW_LEFT_THRESHOLD = 100

export function teamInitials(name: string): string {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    // [...word][0] rather than word[0], and slice after the map rather than over the joined
    // string: indexing a string walks UTF-16 code units, so a name beginning above the BMP
    // yields half a surrogate pair and the badge draws U+FFFD. Same split between code unit
    // and code point that RawPassword.validate was carrying.
    .map((word) => [...word][0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
  return initials || '?'
}

export function upcomingShowtimes(match: Match, now = Date.now()): Showtime[] {
  return match.showtimes
    .filter((showtime) => new Date(showtime.startTime).getTime() > now)
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
}

/** The next showtime that has not started yet. */
export function nearestShowtime(match: Match): Showtime | undefined {
  return upcomingShowtimes(match)[0]
}

export function ticketsRemaining(match: Match): number {
  return upcomingShowtimes(match).reduce((sum, showtime) => sum + showtime.availableSeats, 0)
}

export type CatalogStatus = 'closed' | 'sold_out' | 'few_left' | 'on_sale'

export function catalogStatus(match: Match): CatalogStatus {
  if (match.status !== 'PUBLISHED' || upcomingShowtimes(match).length === 0) return 'closed'
  if (ticketsRemaining(match) <= 0) return 'sold_out'
  if (ticketsRemaining(match) < FEW_LEFT_THRESHOLD) return 'few_left'
  return 'on_sale'
}
