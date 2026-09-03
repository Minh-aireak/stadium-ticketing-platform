import { AxiosError, AxiosHeaders } from 'axios'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { LoginPage } from './LoginPage'

/*
 * AuthContext#login is a bare `await authApi.login(...)` with no catch of its own, so whatever
 * axios rejects with reaches this page untouched — and api.ts's response interceptor skips its
 * 401-refresh retry for every URL containing /auth/, so nothing rewrites a login failure on the
 * way back either. Mocking useAuth therefore models the real rejection exactly.
 */
const login = vi.fn()
vi.mock('@/features/auth/AuthContext', () => {
  const api = { login: (...args: unknown[]) => login(...args) }
  return { useAuth: () => api }
})

function problemError(
  status: number,
  typeSuffix: string,
  detail: string,
  headers: Record<string, string> = {},
): AxiosError {
  const error = new AxiosError('Request failed', 'ERR_BAD_RESPONSE')
  error.config = { headers: new AxiosHeaders() } as never
  error.response = {
    status,
    statusText: '',
    data: { type: `https://aireak.com/errors/${typeSuffix}`, status, detail },
    headers,
    config: error.config,
  } as never
  return error
}

function renderLogin(from?: string) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: from ? { from } : null }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/account" element={<div>trang tài khoản</div>} />
        <Route path="/" element={<div>trang chủ</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function signIn() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'khach@example.com' } })
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'Matkhau1' } })
  fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }))
}

beforeEach(() => {
  login.mockReset()
})

describe('LoginPage', () => {
  /**
   * LoginService raises InvalidCredentialsException("Invalid credentials") for all four of its
   * refusals — unknown email, wrong password, non-ACTIVE account, throttled — and AuthController
   * answers 401 with that literal as the ProblemDetail detail. It is by a wide margin the most
   * common error in the product, and it was reaching the customer in English.
   */
  it('refuses a bad password in Vietnamese, not with "Invalid credentials"', async () => {
    login.mockRejectedValue(problemError(401, 'invalid-credentials', 'Invalid credentials'))
    renderLogin()
    signIn()

    await waitFor(() =>
      expect(screen.getByText('Email hoặc mật khẩu không đúng.')).toBeDefined(),
    )
    expect(screen.queryByText('Invalid credentials')).toBeNull()
  })

  /**
   * The other failure this form has: Redis down while LoginService writes the refresh session
   * (RefreshSessionStoreUnavailableException, deliberately not a DomainException, answered 503
   * with a constant detail). 5xx, so the correlation id comes with it.
   */
  it('names a session-store outage in Vietnamese, with the id to quote', async () => {
    login.mockRejectedValue(
      problemError(503, 'session-store-unavailable', 'Authentication service temporarily unavailable', {
        'x-correlation-id': 'corr-42',
      }),
    )
    renderLogin()
    signIn()

    await waitFor(() =>
      expect(
        screen.getByText(
          'Hệ thống đăng nhập đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-42)',
        ),
      ).toBeDefined(),
    )
  })

  /** Guard: the success path still lands on the route ProtectedRoute caught the customer at. */
  it('returns the customer to the page the route guard caught them on', async () => {
    login.mockResolvedValue(undefined)
    renderLogin('/account')
    signIn()

    await waitFor(() => expect(screen.getByText('trang tài khoản')).toBeDefined())
  })

  /** Guard: a refused attempt leaves the form usable rather than stuck on "Đang đăng nhập…". */
  it('re-enables the form after a refusal', async () => {
    login.mockRejectedValue(problemError(401, 'invalid-credentials', 'Invalid credentials'))
    renderLogin()
    signIn()

    await waitFor(() =>
      expect(screen.getByText('Email hoặc mật khẩu không đúng.')).toBeDefined(),
    )
    const button = screen.getByRole('button', { name: 'Đăng nhập' }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
  })
})
