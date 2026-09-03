import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { StadiumSeatMap } from './StadiumSeatMap'
import { buildStadiumMap } from './stadiumLayout'
import type { Seat } from './types'

function seat(code: string): Seat {
  return {
    code,
    row: code.slice(0, 1),
    number: Number(code.slice(1)),
    status: 'available',
    price: 100000,
    tier: 'standard',
    heldByYou: false,
  }
}

/**
 * Row A of my-dinh is 14 slots per stand and the backend row holds 56 seats, so a row of eight
 * spreads two seats to each stand: north gets A1 and A2. fillRowSlots then places two seats at
 * the ends of a fourteen-slot arc — slot 0 and slot 13 — which is what makes the naming visible.
 */
function renderStadium() {
  const stadiumMap = buildStadiumMap(
    ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8'].map(seat),
    'my-dinh',
  )
  if (!stadiumMap) throw new Error('buildStadiumMap returned null for my-dinh')
  return render(
    <StadiumSeatMap
      stadium={stadiumMap.stadium}
      zones={stadiumMap.zones}
      selected={[]}
      currency="VND"
      onToggle={() => {}}
      onClearSelection={() => {}}
    />,
  )
}

beforeEach(() => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
})

describe('StadiumSeatMap seat naming', () => {
  /**
   * The regression. A seat's accessible name and tooltip were built as
   * `${row.label}${displayNumber}`, where displayNumber is the seat's slot position inside its
   * stand — not its seat number. Seat A2, sitting in slot 13 of the north stand, was therefore
   * announced and titled "A14", which is itself a real seat elsewhere in the same row. The true
   * code was present further along the same string, so the name a screen reader reads first, and
   * the one a tooltip leads with, belonged to a different seat.
   */
  it('names a seat by its own code, never by its slot position', () => {
    renderStadium()

    expect(screen.queryAllByLabelText(/^A14\b/)).toHaveLength(0)
    expect(screen.getByLabelText(/^Ghế A2,/)).toBeDefined()
    expect(screen.getByLabelText(/^Ghế A8,/)).toBeDefined()
  })

  /**
   * The same fabricated name reached the two places a sighted customer actually reads. The
   * tooltip was `${row.label}${displayNumber} · Mã ${seat.code} · …` — "A14 · Mã A2 · 100.000 ₫",
   * leading with the wrong seat — and the number drawn inside the rectangle was `{displayNumber}`,
   * so seat A2 had a "14" printed on it. Pinning only the aria-label left two thirds of the fix
   * free to regress in silence.
   */
  it('titles a seat and prints its number by its own code, never by its slot position', () => {
    renderStadium()

    const a2 = screen.getByLabelText(/^Ghế A2,/)

    // Not a plain-space literal: Intl separates the amount from the symbol with U+00A0.
    expect(a2.querySelector('title')?.textContent).toMatch(/^A2 · standard · 100\.000\s₫$/u)
    expect(a2.querySelector('text')?.textContent).toBe('2')
  })

  it('still labels an empty slot by its position, since it has no seat to name', () => {
    renderStadium()

    expect(screen.queryAllByLabelText(/^Hàng A, vị trí \d+, chưa khả dụng$/).length).toBeGreaterThan(0)
  })
})
