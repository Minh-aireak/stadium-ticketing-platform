import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Match } from '@/features/matches/types'
import { FeaturedMatches } from './FeaturedMatches'

const listMatches = vi.fn()

vi.mock('@/features/matches/matchesApi', () => ({
  listMatches: (...args: unknown[]) => listMatches(...args),
}))
vi.mock('@/hooks/useToast', () => ({ useToast: () => ({ toast: vi.fn() }) }))

function match(matchId: string, competition: string): Match {
  return {
    matchId,
    homeTeam: `${matchId} home`,
    awayTeam: `${matchId} away`,
    competition,
    status: 'PUBLISHED',
    createdAt: new Date(Date.now() - 86_400_000).toISOString(),
    showtimes: [
      {
        showtimeId: `${matchId}-s1`,
        startTime: new Date(Date.now() + 30 * 86_400_000).toISOString(),
        stadiumId: 'my-dinh',
        stadiumName: 'Mỹ Đình',
        totalSeats: 432,
        availableSeats: 400,
        basePrice: 100000,
        currency: 'VND',
      },
    ],
  }
}

const allMatches = [match('m1', 'V-League'), match('m2', 'AFC Cup')]

beforeEach(() => {
  // framer-motion's whileInView needs one; jsdom ships no implementation.
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() {
        return []
      }
    },
  )
  listMatches.mockImplementation(({ q }: { q?: string }) => {
    const items = q ? allMatches.filter((m) => m.competition === q) : allMatches
    return Promise.resolve({ items, totalElements: items.length, page: 0, size: 8 })
  })
})

function renderFeatured() {
  return render(
    <MemoryRouter>
      <FeaturedMatches />
    </MemoryRouter>,
  )
}

describe('FeaturedMatches league chips', () => {
  it('offers a chip per league in the loaded page', async () => {
    renderFeatured()

    await waitFor(() => expect(screen.getByRole('button', { name: 'V-League' })).toBeDefined())
    expect(screen.getByRole('button', { name: 'AFC Cup' })).toBeDefined()
  })

  /**
   * The regression. The chip list was derived from `matches` — the page currently on screen — so
   * picking a league narrowed the results to that league and every other chip vanished with
   * them. A filter whose options disappear the moment you use one cannot be switched: the only
   * way to reach a second league was to notice that the search box had been filled in and edit
   * it by hand.
   */
  it('keeps the other leagues reachable after one is picked', async () => {
    renderFeatured()

    await waitFor(() => expect(screen.getByRole('button', { name: 'AFC Cup' })).toBeDefined())
    screen.getByRole('button', { name: 'V-League' }).click()

    await waitFor(() => expect(listMatches).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'V-League' }),
    ))
    await waitFor(() => expect(screen.getByRole('button', { name: 'AFC Cup' })).toBeDefined())
  })
})
