import { AxiosError, AxiosHeaders } from 'axios'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ResetPasswordPage } from './ResetPasswordPage'

const resetPassword = vi.fn()
vi.mock('@/features/auth/authApi', () => ({
  resetPassword: (...args: unknown[]) => resetPassword(...args),
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

function renderReset(search = '?token=one-time-token') {
  return render(
    <MemoryRouter initialEntries={[`/reset-password${search}`]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

async function submitNewPassword() {
  fireEvent.change(screen.getByLabelText('Mật khẩu mới'), { target: { value: 'Matkhau1' } })
  fireEvent.change(screen.getByLabelText('Nhập lại mật khẩu mới'), { target: { value: 'Matkhau1' } })
  const button = screen.getByRole('button', { name: 'Đổi mật khẩu' }) as HTMLButtonElement
  await waitFor(() => expect(button.disabled).toBe(false))
  fireEvent.click(button)
}

beforeEach(() => {
  resetPassword.mockReset()
})

describe('ResetPasswordPage', () => {
  /**
   * ResetPasswordService touches Redis twice, and either call can raise
   * PasswordResetTokenStoreUnavailableException — a 503 with a constant English detail. It must
   * stay an inline error: the link is still good, so replacing the form with "request a new one"
   * would send the customer to redo something that never failed.
   */
  it('names a reset-store outage in Vietnamese without discarding the form', async () => {
    resetPassword.mockRejectedValue(
      problemError(503, 'reset-token-store-unavailable', 'Password reset is temporarily unavailable', {
        'x-correlation-id': 'corr-3',
      }),
    )
    renderReset()
    await submitNewPassword()

    await waitFor(() =>
      expect(
        screen.getByText(
          'Chức năng đặt lại mật khẩu đang tạm thời gián đoạn. Vui lòng thử lại sau ít phút. (Mã lỗi: corr-3)',
        ),
      ).toBeDefined(),
    )
    expect(screen.getByLabelText('Mật khẩu mới')).toBeDefined()
  })

  /**
   * Guard: InvalidPasswordResetTokenException is a DomainException and so carries the shared
   * `domain-error` type — isInvalidResetTokenError has to keep recognising it by its sentence,
   * and it must keep replacing the form rather than showing an inline error.
   */
  it('still swaps the form for the request-a-new-link panel on a spent token', async () => {
    resetPassword.mockRejectedValue(
      problemError(422, 'domain-error', 'Password reset token is invalid or has expired'),
    )
    renderReset()
    await submitNewPassword()

    await waitFor(() =>
      expect(screen.getByText('Link này đã hết hạn hoặc đã được dùng rồi.')).toBeDefined(),
    )
    expect(screen.queryByLabelText('Mật khẩu mới')).toBeNull()
  })

  /** Guard: a link with no token at all never renders a form to submit. */
  it('says the link is missing its token', () => {
    renderReset('')
    expect(
      screen.getByText('Link đặt lại mật khẩu không hợp lệ — thiếu mã xác thực.'),
    ).toBeDefined()
  })

  /** Guard: the success panel. */
  it('confirms the change and points at login', async () => {
    resetPassword.mockResolvedValue(undefined)
    renderReset()
    await submitNewPassword()

    await waitFor(() =>
      expect(
        screen.getByText('Đổi mật khẩu thành công! Bạn có thể đăng nhập bằng mật khẩu mới.'),
      ).toBeDefined(),
    )
  })
})
