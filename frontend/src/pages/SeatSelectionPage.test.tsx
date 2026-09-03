import { AxiosError, AxiosHeaders } from 'axios'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
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
// One fixed object, created once by the factory: a fresh `{ toast: vi.fn() }` per call would give
// every render a new identity and re-fire each effect that lists `toast` in its deps.
const toast = vi.fn()
vi.mock('@/hooks/useToast', () => {
  const api = { toast: (...args: unknown[]) => toast(...args) }
  return { useToast: () => api }
})

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
  currency: 'VND',
}

function CheckoutStateProbe() {
  return <pre data-testid="checkout-state">{JSON.stringify(useLocation().state)}</pre>
}

function renderSeatSelection(overrides: Partial<SeatSelectionState> = {}) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/matches/match-1/seats', state: { ...state, ...overrides } }]}>
      <Routes>
        <Route path="/matches/:matchId/seats" element={<SeatSelectionPage />} />
        <Route path="/checkout" element={<CheckoutStateProbe />} />
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

  /**
   * The page hard-coded `currency: 'VND'` into the CheckoutState it handed forward, and
   * SeatSelectionState had no currency field to carry the real one — so the showtime's currency,
   * which match-catalog-service stores and payment-service charges in, was dropped between the
   * match detail page and checkout. It is only ever displayed (booking-service overwrites the
   * posted amount and currency with ticket-inventory-service's in saga Step 2b), but it is
   * displayed on the screen where the customer agrees to the charge.
   */
  it('carries the showtime currency through to checkout instead of assuming đồng', async () => {
    getSeatMap.mockResolvedValue({
      showtimeId: 'show-1',
      seats: [seat('A1', { status: 'held', heldByYou: true, price: 12.99 })],
    })

    renderSeatSelection({ currency: 'USD' })

    await waitFor(() => expect(screen.getByText('1 ghế')).toBeDefined())
    // Both the running total and the tier legend price it, so more than one node carries it.
    expect(screen.getAllByText(/US\$/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/₫/)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Tiếp tục thanh toán' }))

    await waitFor(() => expect(screen.getByTestId('checkout-state')).toBeDefined())
    expect(JSON.parse(screen.getByTestId('checkout-state').textContent ?? '{}')).toMatchObject({
      currency: 'USD',
      seatCodes: ['A1'],
    })
  })

  /**
   * The regression. When another shopper already holds the seat just clicked, inventory answers
   * 422 with a ProblemDetail whose detail is English and names the internal showtimeId. Because
   * getErrorMessage renders a domain 422's detail verbatim, the Vietnamese sentence this call site
   * passes as getErrorMessage's fallback could only ever have appeared if the backend had sent no
   * detail at all — so the one case it was written for was the one case that could not reach it,
   * and the customer got "Seats not available for showtime show-1: A1" instead.
   */
  it('explains a seat lost to another shopper in Vietnamese, not in the backend’s English', async () => {
    getSeatMap.mockResolvedValue({ showtimeId: 'show-1', seats: [seat('A1'), seat('A2')] })
    const conflict = new AxiosError('Request failed', 'ERR_BAD_RESPONSE')
    conflict.config = { headers: new AxiosHeaders() } as never
    conflict.response = {
      status: 422,
      statusText: '',
      data: { detail: 'Seats not available for showtime show-1: A1' },
      headers: {},
      config: conflict.config,
    } as never
    holdSeats.mockRejectedValue(conflict)

    renderSeatSelection()

    await waitFor(() => expect(screen.getByTitle(/^A1 ·/)).toBeDefined())
    fireEvent.click(screen.getByTitle(/^A1 ·/))

    await waitFor(() => expect(toast).toHaveBeenCalled())
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Không thể giữ ghế',
        description: 'Ghế này vừa được người khác chọn. Vui lòng chọn ghế khác.',
      }),
    )
  })

  /**
   * The showtime stopped selling while the customer sat on this page — an admin cancelled the
   * match, or its kickoff passed. inventory's requireBookable raises ShowtimeBookingClosedException,
   * another DomainException 422, and its English named the internal showtimeId just as the
   * seats-taken one did. MatchDetailPage no longer offers a cancelled match at all, but it cannot
   * cover a customer already standing here when the cancellation lands.
   */
  it('explains a showtime that stopped selling in Vietnamese', async () => {
    getSeatMap.mockResolvedValue({ showtimeId: 'show-1', seats: [seat('A1'), seat('A2')] })
    const closed = new AxiosError('Request failed', 'ERR_BAD_RESPONSE')
    closed.config = { headers: new AxiosHeaders() } as never
    closed.response = {
      status: 422,
      statusText: '',
      data: { detail: 'Ticket booking is closed for showtime: show-1' },
      headers: {},
      config: closed.config,
    } as never
    holdSeats.mockRejectedValue(closed)

    renderSeatSelection()

    await waitFor(() => expect(screen.getByTitle(/^A1 ·/)).toBeDefined())
    fireEvent.click(screen.getByTitle(/^A1 ·/))

    await waitFor(() => expect(toast).toHaveBeenCalled())
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Không thể giữ ghế',
        description: 'Trận đấu này đã ngừng bán vé. Vui lòng chọn trận đấu khác.',
      }),
    )
  })

  /**
   * Guard for the pair above: a catalog outage is NOT a closed booking window. c8ff01a split
   * ShowtimeCatalogUnavailableException off DomainException precisely so it answers 503, and it
   * must keep reaching the customer as an outage carrying the correlation id getErrorMessage
   * appends to a 5xx — the whole point of that split.
   *
   * <p>The body below is SeatInventoryController#handleCatalogUnavailable's, type and all. That
   * detail is a constant, so errors.ts answers it by type rather than printing the English.
   */
  it('still reports a catalog outage as an outage, with its correlation id', async () => {
    getSeatMap.mockResolvedValue({ showtimeId: 'show-1', seats: [seat('A1'), seat('A2')] })
    const outage = new AxiosError('Request failed', 'ERR_BAD_RESPONSE')
    outage.config = { headers: new AxiosHeaders() } as never
    outage.response = {
      status: 503,
      statusText: '',
      data: {
        type: 'https://aireak.com/errors/catalog-unavailable',
        status: 503,
        detail: 'Ticket availability cannot be verified right now',
      },
      headers: { 'x-correlation-id': 'corr-42' },
      config: outage.config,
    } as never
    holdSeats.mockRejectedValue(outage)

    renderSeatSelection()

    await waitFor(() => expect(screen.getByTitle(/^A1 ·/)).toBeDefined())
    fireEvent.click(screen.getByTitle(/^A1 ·/))

    await waitFor(() => expect(toast).toHaveBeenCalled())
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        description:
          'Chưa kiểm tra được tình trạng vé lúc này. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-42)',
      }),
    )
  })
})
