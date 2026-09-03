import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Match, MatchStatus, Showtime } from '@/features/matches/types'
import { MatchDetailPage } from './MatchDetailPage'

const getMatch = vi.fn()
vi.mock('@/features/matches/matchesApi', () => ({
  getMatch: (...args: unknown[]) => getMatch(...args),
}))
// One fixed object, created once by the factory: a fresh `{ toast: vi.fn() }` per call would give
// every render a new identity and re-fire the load effect, which lists `toast` in its deps.
const toast = vi.fn()
vi.mock('@/hooks/useToast', () => {
  const api = { toast: (...args: unknown[]) => toast(...args) }
  return { useToast: () => api }
})

const DAY = 86_400_000

function showtime(overrides: Partial<Showtime> = {}): Showtime {
  return {
    showtimeId: 'show-1',
    startTime: new Date(Date.now() + 7 * DAY).toISOString(),
    stadiumId: 'my-dinh',
    stadiumName: 'Sân vận động Mỹ Đình',
    totalSeats: 40000,
    availableSeats: 39000,
    basePrice: 200000,
    currency: 'VND',
    ...overrides,
  }
}

function match(status: MatchStatus = 'PUBLISHED', showtimes: Showtime[] = [showtime()]): Match {
  return {
    matchId: 'match-1',
    homeTeam: 'Hà Nội FC',
    awayTeam: 'Nam Định',
    competition: 'V.League 1',
    status,
    createdAt: new Date(Date.now() - DAY).toISOString(),
    showtimes,
  }
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/matches/match-1']}>
      <Routes>
        <Route path="/matches/:matchId" element={<MatchDetailPage />} />
        <Route path="*" element={<div>trang chủ</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Waits for the load to settle — the page renders a spinner and no heading until then. */
async function loaded() {
  await waitFor(() => expect(screen.getByRole('heading')).toBeDefined())
}

beforeEach(() => {
  getMatch.mockReset()
  toast.mockReset()
})

describe('MatchDetailPage booking window', () => {
  // GET /matches/{matchId} serves a match in any status — only the browse list filters to
  // PUBLISHED (MatchCatalogService#listMatches) — so these three reach a customer who kept the
  // URL, or who was on the page when an admin cancelled the match.
  it.each<[MatchStatus, string]>([
    ['CANCELLED', 'Trận đấu đã bị huỷ'],
    ['COMPLETED', 'Trận đấu đã kết thúc'],
    ['DRAFT', 'Trận đấu chưa mở bán'],
  ])('does not offer seats for a %s match', async (status, headline) => {
    getMatch.mockResolvedValue(match(status))
    renderDetail()
    await loaded()

    expect(screen.queryByRole('link', { name: 'Chọn ghế' })).toBeNull()
    expect(screen.getByText(headline)).toBeDefined()
    // The seat count is what made the page read as on sale.
    expect(screen.queryByText('Còn 39.000 vé')).toBeNull()
  })

  it('still offers seats for a PUBLISHED match', async () => {
    getMatch.mockResolvedValue(match('PUBLISHED'))
    renderDetail()
    await loaded()

    const link = screen.getByRole('link', { name: 'Chọn ghế' })
    expect(link.getAttribute('href')).toBe('/matches/match-1/seats')
    expect(screen.getByText('Còn 39.000 vé')).toBeDefined()
  })

  it('still refuses a showtime that has already kicked off', async () => {
    getMatch.mockResolvedValue(match('PUBLISHED', [showtime({ startTime: new Date(Date.now() - DAY).toISOString() })]))
    renderDetail()
    await loaded()

    expect(screen.queryByRole('link', { name: 'Chọn ghế' })).toBeNull()
    expect(screen.getByText('Đã diễn ra')).toBeDefined()
  })

  it('still refuses a showtime with no seats left', async () => {
    getMatch.mockResolvedValue(match('PUBLISHED', [showtime({ availableSeats: 0 })]))
    renderDetail()
    await loaded()

    expect(screen.queryByRole('link', { name: 'Chọn ghế' })).toBeNull()
    // Once as the headline, once on the disabled button.
    expect(screen.getAllByText('Đã hết vé').length).toBe(2)
  })
})
