import { AxiosError, AxiosHeaders } from 'axios'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ForgotPasswordPage } from './ForgotPasswordPage'

const forgotPassword = vi.fn()
vi.mock('@/features/auth/authApi', () => ({
  forgotPassword: (...args: unknown[]) => forgotPassword(...args),
}))

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

async function requestLink() {
  render(
    <MemoryRouter>
      <ForgotPasswordPage />
    </MemoryRouter>,
  )
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'khach@example.com' } })
  const button = screen.getByRole('button', { name: 'Gửi link đặt lại mật khẩu' }) as HTMLButtonElement
  await waitFor(() => expect(button.disabled).toBe(false))
  fireEvent.click(button)
}

beforeEach(() => {
  forgotPassword.mockReset()
})

describe('ForgotPasswordPage', () => {
  /**
   * The only way this endpoint fails: Redis down while RequestPasswordResetService writes the
   * one-time token. AuthController answers 503 with a constant English detail, which errors.ts
   * rendered verbatim into a Vietnamese form.
   */
  it('names a reset-store outage in Vietnamese', async () => {
    forgotPassword.mockRejectedValue(
      problemError(503, 'reset-token-store-unavailable', 'Password reset is temporarily unavailable', {
        'x-correlation-id': 'corr-9',
      }),
    )
    await requestLink()

    await waitFor(() =>
      expect(
        screen.getByText(
          'Chức năng đặt lại mật khẩu đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-9)',
        ),
      ).toBeDefined(),
    )
    expect(screen.queryByText('Password reset is temporarily unavailable')).toBeNull()
  })

  /**
   * Guard: the 204 confirmation must stay worded so it says nothing about whether the address has
   * an account — the backend answers identically either way on purpose.
   */
  it('confirms without saying whether the address is registered', async () => {
    forgotPassword.mockResolvedValue(undefined)
    await requestLink()

    await waitFor(() => expect(screen.getByText(/đang gắn với một tài khoản/)).toBeDefined())
    expect(screen.getByText('Quay lại đăng nhập')).toBeDefined()
  })
})
