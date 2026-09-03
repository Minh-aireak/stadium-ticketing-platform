import { describe, expect, it } from 'vitest'

import { catalogStatus, nearestShowtime, teamInitials, ticketsRemaining, upcomingShowtimes } from './matchView'
import type { Match, MatchStatus, Showtime } from './types'

const HOUR = 1000 * 60 * 60

function showtime(id: string, offsetMs: number, availableSeats: number): Showtime {
  return {
    showtimeId: id,
    startTime: new Date(Date.now() + offsetMs).toISOString(),
    stadiumId: 'my-dinh',
    stadiumName: 'Sân Mỹ Đình',
    totalSeats: 432,
    availableSeats,
    basePrice: 200000,
    currency: 'VND',
  }
}

function match(showtimes: Showtime[], status: MatchStatus = 'PUBLISHED'): Match {
  return {
    matchId: 'm-1',
    homeTeam: 'Hà Nội FC',
    awayTeam: 'Sông Lam Nghệ An',
    competition: 'V.League 1',
    status,
    createdAt: new Date(Date.now() - 30 * HOUR).toISOString(),
    showtimes,
  }
}

/** Lone surrogates are the failure mode: they render as U+FFFD, not as a letter. */
function hasLoneSurrogate(value: string): boolean {
  return [...value].some((char) => {
    const code = char.codePointAt(0) as number
    return code >= 0xd800 && code <= 0xdfff
  })
}

describe('teamInitials', () => {
  it('takes the first letter of the first two words', () => {
    expect(teamInitials('Hà Nội FC')).toBe('HN')
    expect(teamInitials('Manchester United')).toBe('MU')
    expect(teamInitials('sông lam nghệ an')).toBe('SL')
    expect(teamInitials('Arsenal')).toBe('A')
  })

  it('falls back to ? when there is no letter to take', () => {
    expect(teamInitials('')).toBe('?')
    expect(teamInitials('   ')).toBe('?')
  })

  /**
   * AdminPage's team-name inputs are free text with no charset bound, so a name can begin
   * above the BMP. Indexing with [0] hands back half a surrogate pair, which the badge draws
   * as a replacement glyph — the same UTF-16-vs-code-point split RawPassword.validate had.
   */
  it('takes whole code points, not UTF-16 code units', () => {
    expect(teamInitials('🇻🇳 Việt Nam')).toBe('🇻V')
    expect(teamInitials('𝐑eal Madrid')).toBe('𝐑M')
    expect(hasLoneSurrogate(teamInitials('🇻🇳 Việt Nam'))).toBe(false)
    expect(hasLoneSurrogate(teamInitials('𝐑eal Madrid'))).toBe(false)
  })
})

describe('upcomingShowtimes', () => {
  it('drops what has started and sorts what has not', () => {
    const past = showtime('past', -HOUR, 100)
    const soon = showtime('soon', HOUR, 100)
    const later = showtime('later', 5 * HOUR, 100)
    const result = upcomingShowtimes(match([later, past, soon]))
    expect(result.map((s) => s.showtimeId)).toEqual(['soon', 'later'])
  })

  it('honours an injected clock', () => {
    const soon = showtime('soon', HOUR, 100)
    expect(upcomingShowtimes(match([soon]), Date.now() + 2 * HOUR)).toEqual([])
  })
})

describe('nearestShowtime / ticketsRemaining', () => {
  it('picks the earliest showtime still ahead', () => {
    const result = nearestShowtime(match([showtime('later', 5 * HOUR, 1), showtime('soon', HOUR, 1)]))
    expect(result?.showtimeId).toBe('soon')
  })

  it('is undefined when nothing is ahead', () => {
    expect(nearestShowtime(match([showtime('past', -HOUR, 100)]))).toBeUndefined()
  })

  it('sums only the seats still buyable', () => {
    expect(ticketsRemaining(match([showtime('past', -HOUR, 500), showtime('soon', HOUR, 40)]))).toBe(40)
  })
})

describe('catalogStatus', () => {
  it.each<[MatchStatus]>([['DRAFT'], ['COMPLETED'], ['CANCELLED']])(
    'is closed while the match is %s',
    (status) => {
      expect(catalogStatus(match([showtime('soon', HOUR, 500)], status))).toBe('closed')
    },
  )

  it('is closed once every showtime has started', () => {
    expect(catalogStatus(match([showtime('past', -HOUR, 500)]))).toBe('closed')
  })

  it('is sold_out when nothing is left across the upcoming showtimes', () => {
    expect(catalogStatus(match([showtime('soon', HOUR, 0)]))).toBe('sold_out')
  })

  it('switches to few_left just under the cutoff and back at it', () => {
    expect(catalogStatus(match([showtime('soon', HOUR, 99)]))).toBe('few_left')
    expect(catalogStatus(match([showtime('soon', HOUR, 100)]))).toBe('on_sale')
  })
})
