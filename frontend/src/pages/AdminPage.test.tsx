import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AdminPage } from './AdminPage'
import type { StadiumSummary } from '@/features/admin/types'
import type { Match } from '@/features/matches/types'

const listActiveMatches = vi.fn()
const listStadiums = vi.fn()
const completeMatch = vi.fn()
const cancelMatch = vi.fn()
const createMatch = vi.fn()
const addShowtime = vi.fn()
const publishMatch = vi.fn()
const toast = vi.fn()

vi.mock('@/features/admin/adminMatchesApi', () => ({
  listActiveMatches: (...args: unknown[]) => listActiveMatches(...args),
  listStadiums: (...args: unknown[]) => listStadiums(...args),
  completeMatch: (...args: unknown[]) => completeMatch(...args),
  cancelMatch: (...args: unknown[]) => cancelMatch(...args),
  createMatch: (...args: unknown[]) => createMatch(...args),
  addShowtime: (...args: unknown[]) => addShowtime(...args),
  publishMatch: (...args: unknown[]) => publishMatch(...args),
}))
vi.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'admin-1', email: 'admin@stadium.test', role: 'ADMIN' } }),
}))
// One object for every render, the way ToastProvider's useMemo hands one out. A fresh object
// per call would change the identity AdminPage's stadium effect depends on and re-run it on
// every render, which the real hook never does.
vi.mock('@/hooks/useToast', () => {
  const api = { toast: (...args: unknown[]) => toast(...args) }
  return { useToast: () => api }
})

const PAGE_SIZE = 10

const stadium: StadiumSummary = {
  id: 'my-dinh',
  name: 'Sân Mỹ Đình',
  levels: 3,
  totalSeats: 432,
  design: 'OVAL',
}

function buildMatch(index: number): Match {
  return {
    matchId: `m-${index}`,
    homeTeam: `Trận ${index}`,
    awayTeam: `Đối thủ ${index}`,
    competition: 'V.League 1',
    status: 'PUBLISHED',
    createdAt: '2026-01-01T00:00:00Z',
    showtimes: [],
  }
}

/** The server-side list, so removing a match really shortens what the next page request sees. */
let live: Match[] = []

beforeEach(() => {
  live = Array.from({ length: 11 }, (_, i) => buildMatch(i + 1))
  listActiveMatches.mockImplementation(
    ({ page = 0, size = PAGE_SIZE }: { page?: number; size?: number }) =>
      Promise.resolve({
        items: live.slice(page * size, page * size + size),
        totalElements: live.length,
        page,
        size,
      }),
  )
  listStadiums.mockResolvedValue([stadium])
  completeMatch.mockImplementation((matchId: string) => {
    live = live.filter((m) => m.matchId !== matchId)
    return Promise.resolve()
  })
  cancelMatch.mockImplementation((matchId: string) => {
    live = live.filter((m) => m.matchId !== matchId)
    return Promise.resolve()
  })
  createMatch.mockResolvedValue({ matchId: 'm-new' })
  addShowtime.mockResolvedValue(undefined)
  publishMatch.mockResolvedValue(undefined)
})

async function goToLastPage() {
  render(<AdminPage />)
  await screen.findByText('Trận 1 vs Đối thủ 1')
  fireEvent.click(screen.getByRole('button', { name: 'Sau' }))
  await screen.findByText('Trận 11 vs Đối thủ 11')
}

describe('AdminPage match list', () => {
  it('renders the page the server hands back', async () => {
    render(<AdminPage />)
    await screen.findByText('Trận 1 vs Đối thủ 1')
    expect(screen.getByText('Trang 1/2')).toBeDefined()
    expect(screen.queryByText('Trận 11 vs Đối thủ 11')).toBeNull()
  })

  /**
   * The pager lives inside the `matches.length > 0` branch, so a page that comes back empty
   * takes "Trước" away with it: completing the only match on the last page leaves the admin
   * looking at "Chưa có trận đấu nào đang mở bán." with ten matches one page back and no
   * control left to reach them. Only a full reload clears it — `page` is component state.
   */
  it('steps back a page when completing the last match on it empties the page', async () => {
    await goToLastPage()

    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất' }))

    await screen.findByText('Trận 1 vs Đối thủ 1')
    expect(screen.queryByText('Chưa có trận đấu nào đang mở bán.')).toBeNull()
  })

  it('steps back a page when cancelling the last match on it empties the page', async () => {
    await goToLastPage()

    fireEvent.click(screen.getByRole('button', { name: 'Huỷ trận' }))
    fireEvent.change(screen.getByPlaceholderText('Lý do huỷ trận'), { target: { value: 'Bão' } })
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận huỷ' }))

    await screen.findByText('Trận 1 vs Đối thủ 1')
    expect(screen.queryByText('Chưa có trận đấu nào đang mở bán.')).toBeNull()
  })

  it('stays put when the page still has rows after a completion', async () => {
    render(<AdminPage />)
    await screen.findByText('Trận 1 vs Đối thủ 1')

    fireEvent.click(screen.getAllByRole('button', { name: 'Hoàn tất' })[0])

    await waitFor(() => expect(screen.queryByText('Trận 1 vs Đối thủ 1')).toBeNull())
    expect(screen.getByText('Trận 2 vs Đối thủ 2')).toBeDefined()
    expect(listActiveMatches.mock.calls.at(-1)?.[0]).toMatchObject({ page: 0 })
  })
})
