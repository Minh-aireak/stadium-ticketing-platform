import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Seat } from '@/features/seats/types'
import { SeatSelectionPage } from './SeatSelectionPage'
import type { SeatSelectionState } from './SeatSelectionPage'

const getSeatMap = vi.fn()
const getSeatLayout = vi.fn()
const holdSeats = vi.fn()
const unholdSeats = vi.fn()

vi.mock('@/features/seats/seatsApi', () => ({
  getSeatMap: (...args: unknown[]) => getSeatMap(...args),
  getSeatLayout: (...args: unknown[]) => getSeatLayout(...args),
  holdSeats: (...args: unknown[]) => holdSeats(...args),
  unholdSeats: (...args: unknown[]) => unholdSeats(...args),
}))
vi.mock('@/hooks/useToast', () => ({ useToast: () => ({ toast: vi.fn() }) }))

function seat(code: string, overrides: Partial<Seat> = {}): Seat {
  return {
    code,
    row: code.slice(0, 1),
    number: Number(code.slice(1)),
    status: 'available',
    price: 100000,
    tier: 'standard',
    heldByYou: false,
    ...overrides,
  }
}

const state: SeatSelectionState = {
  matchLabel: 'A vs B',
  showtimeId: 'show-1',
  // Well clear of the "already kicked off" guard, which otherwise redirects before rendering.
  startTime: new Date(Date.now() + 86_400_000).toISOString(),
  // Not a stadium the layout table knows, so the page falls back to the plain grid seat map.
  stadiumId: 'stadium-unknown',
}

function renderSeatSelection() {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/matches/match-1/seats', state }]}>
      <Routes>
        <Route path="/matches/:matchId/seats" element={<SeatSelectionPage />} />
        <Route path="*" element={<div>redirected</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  getSeatLayout.mockResolvedValue(null)
  holdSeats.mockResolvedValue({ totalPrice: 0 })
  unholdSeats.mockResolvedValue(undefined)
})

describe('SeatSelectionPage', () => {
  it('starts with nothing selected when the customer holds no seats', async () => {
    getSeatMap.mockResolvedValue({ showtimeId: 'show-1', seats: [seat('A1'), seat('A2')] })

    renderSeatSelection()

    await waitFor(() => expect(screen.getByText('Chưa chọn ghế nào')).toBeDefined())
    expect(screen.getByRole('button', { name: 'Tiếp tục thanh toán' }).hasAttribute('disabled')).toBe(true)
  })

  /**
   * The regression. Reloading this page — or backing into it out of checkout, which deliberately
   * keeps the holds — remounts it with an empty selection while the holds are still live in
   * Redis. The seat map reported them simply as 'held', identical to another shopper's, and both
   * seat maps only keep a held seat clickable when it is already selected. So the customer's own
   * seats came back greyed out and unselectable, with no route forward for the 10-minute TTL.
   */
  it('re-adopts the seats this customer is already holding', async () => {
    getSeatMap.mockResolvedValue({
      showtimeId: 'show-1',
      seats: [
        seat('A1', { status: 'held', heldByYou: true }),
        seat('A2', { status: 'held', heldByYou: true }),
        seat('A3', { status: 'held', heldByYou: false }),
        seat('A4'),
      ],
    })

    renderSeatSelection()

    await waitFor(() => expect(screen.getByText('2 ghế')).toBeDefined())
    expect(screen.getByText('A1')).toBeDefined()
    expect(screen.getByText('A2')).toBeDefined()
    expect(screen.queryByText('A3')).toBeNull()
    expect(screen.getByRole('button', { name: 'Tiếp tục thanh toán' }).hasAttribute('disabled')).toBe(false)
  })

  it('leaves another shopper’s hold alone', async () => {
    getSeatMap.mockResolvedValue({
      showtimeId: 'show-1',
      seats: [seat('A1', { status: 'held', heldByYou: false })],
    })

    renderSeatSelection()

    await waitFor(() => expect(screen.getByText('Chưa chọn ghế nào')).toBeDefined())
    expect(holdSeats).not.toHaveBeenCalled()
  })
})
