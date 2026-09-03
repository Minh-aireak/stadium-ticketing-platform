import { AxiosError, AxiosHeaders } from 'axios'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { RegisterPage } from './RegisterPage'

const registerAccount = vi.fn()
vi.mock('@/features/auth/AuthContext', () => {
  const api = { register: (...args: unknown[]) => registerAccount(...args) }
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

function renderRegister() {
  return render(
    <MemoryRouter>
      <RegisterPage />
    </MemoryRouter>,
  )
}

/** Fills the zod-gated form and submits once react-hook-form has re-enabled the button. */
async function signUp() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'moi@example.com' } })
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'Matkhau1' } })
  fireEvent.change(screen.getByLabelText('Nhập lại mật khẩu'), { target: { value: 'Matkhau1' } })
  const button = screen.getByRole('button', { name: 'Đăng ký' }) as HTMLButtonElement
  await waitFor(() => expect(button.disabled).toBe(false))
  fireEvent.click(button)
}

beforeEach(() => {
  registerAccount.mockReset()
})

describe('RegisterPage', () => {
  /**
   * Guard: EmailAlreadyRegisteredException is a DomainException, so it carries the one
   * `domain-error` type every DomainException on the platform shares — the type table must not
   * claim it, and isDuplicateEmailError has to keep recognising it by its sentence.
   */
  it('still turns the duplicate-email 422 into an inline field error', async () => {
    registerAccount.mockRejectedValue(
      problemError(422, 'domain-error', 'Email already registered: moi@example.com'),
    )
    renderRegister()
    await signUp()

    await waitFor(() =>
      expect(
        screen.getByText('Email này đã được đăng ký. Vui lòng dùng email khác hoặc đăng nhập.'),
      ).toBeDefined(),
    )
    expect(screen.queryByText('Email already registered: moi@example.com')).toBeNull()
  })

  /**
   * RegisterAccountService stores the verification token inside the request, so a Redis outage
   * surfaces here as VerificationTokenStoreUnavailableException — a 503 that used to arrive as an
   * HTML page and now arrives as a problem document this page can read.
   */
  it('names a verification-store outage in Vietnamese', async () => {
    registerAccount.mockRejectedValue(
      problemError(
        503,
        'verification-token-store-unavailable',
        'Email verification is temporarily unavailable',
        { 'x-correlation-id': 'corr-7' },
      ),
    )
    renderRegister()
    await signUp()

    await waitFor(() =>
      expect(
        screen.getByText(
          'Chưa gửi được email xác minh lúc này. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-7)',
        ),
      ).toBeDefined(),
    )
  })

  /** Guard: the success panel, which is the only thing that tells the customer to check mail. */
  it('asks the customer to verify their email on success', async () => {
    registerAccount.mockResolvedValue('acc-1')
    renderRegister()
    await signUp()

    await waitFor(() =>
      expect(
        screen.getByText('Đăng ký thành công! Vui lòng kiểm tra email để xác minh tài khoản.'),
      ).toBeDefined(),
    )
  })
})
