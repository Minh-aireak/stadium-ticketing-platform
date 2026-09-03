import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { Navbar } from './Navbar'

vi.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({ user: null, status: 'anonymous', logout: vi.fn() }),
}))
vi.mock('@/hooks/useToast', () => ({ useToast: () => ({ toast: vi.fn() }) }))

function renderNavbar() {
  return render(
    <MemoryRouter>
      <Navbar />
    </MemoryRouter>,
  )
}

describe('Navbar mobile menu toggle', () => {
  /**
   * One button both opens and closes the menu, and its icon swaps to match — but its
   * aria-label was the constant "Mở menu". A screen reader user was told the control opens the
   * menu at the very moment it would close it, and got no announcement that anything had
   * changed state.
   */
  it('says what it will do, in both states', () => {
    renderNavbar()

    const toggle = screen.getByRole('button', { name: 'Mở menu' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')

    fireEvent.click(toggle)

    expect(screen.getByRole('button', { name: 'Đóng menu' })).toBe(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
  })
})
