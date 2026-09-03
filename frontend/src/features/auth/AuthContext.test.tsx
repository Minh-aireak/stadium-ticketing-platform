import { render, screen, waitFor } from '@testing-library/react'
import { act } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider, useAuth } from './AuthContext'

const login = vi.fn()
const register = vi.fn()
const logout = vi.fn()
const silentRefresh = vi.fn()

vi.mock('./authApi', () => ({
  login: (...args: unknown[]) => login(...args),
  register: (...args: unknown[]) => register(...args),
  logout: (...args: unknown[]) => logout(...args),
  silentRefresh: (...args: unknown[]) => silentRefresh(...args),
}))

function base64Url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const accessToken = `header.${base64Url({
  sub: 'user-1',
  email: 'a@b.test',
  role: 'USER',
  exp: 9_999_999_999,
  iat: 1,
})}.signature`

let logoutFromUi: () => Promise<void>

function Probe() {
  const auth = useAuth()
  logoutFromUi = auth.logout
  return (
    <div>
      <span data-testid="status">{auth.status}</span>
      <span data-testid="email">{auth.user?.email ?? 'none'}</span>
    </div>
  )
}

async function renderAuthenticated() {
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
  await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'))
}

beforeEach(() => {
  silentRefresh.mockResolvedValue(accessToken)
})

describe('AuthProvider', () => {
  it('restores the session from the refresh cookie on load', async () => {
    await renderAuthenticated()
    expect(screen.getByTestId('email').textContent).toBe('a@b.test')
  })

  it('signs the customer out on a successful logout', async () => {
    logout.mockResolvedValue(undefined)
    await renderAuthenticated()

    await act(async () => {
      await logoutFromUi()
    })

    expect(screen.getByTestId('status').textContent).toBe('anonymous')
    expect(screen.getByTestId('email').textContent).toBe('none')
  })

  /**
   * The regression. authApi.logout drops the in-memory access token in its own finally and then
   * rethrows, so a failed request used to leave the provider still reporting 'authenticated'
   * with the customer's email — a UI claiming a session the app no longer held a token for,
   * which the 401 refresh path then quietly restored off the un-revoked cookie.
   */
  it('still clears the local session when the logout request fails', async () => {
    logout.mockRejectedValue(new Error('network down'))
    await renderAuthenticated()

    await act(async () => {
      await expect(logoutFromUi()).rejects.toThrow('network down')
    })

    expect(screen.getByTestId('status').textContent).toBe('anonymous')
    expect(screen.getByTestId('email').textContent).toBe('none')
  })
})
