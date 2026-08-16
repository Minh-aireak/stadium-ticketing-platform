import { api, refreshAccessToken, setAccessToken } from '@/lib/api'
import type {
  ForgotPasswordRequest,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  RegisterResponse,
  ResetPasswordRequest,
} from './types'

export async function login(payload: LoginRequest): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login', payload)
  setAccessToken(data.accessToken)
  return data
}

export async function register(payload: RegisterRequest): Promise<RegisterResponse> {
  const { data } = await api.post<RegisterResponse>('/auth/register', payload)
  return data
}

/**
 * Requests a reset link. Resolves with 204 whether or not the address has an account —
 * identity-service deliberately answers identically either way so this endpoint can't be used
 * to discover which emails are registered. The UI must not imply otherwise.
 */
export async function forgotPassword(payload: ForgotPasswordRequest): Promise<void> {
  await api.post('/auth/forgot-password', payload)
}

/** Consumes the one-time token from the reset email and sets the new password. */
export async function resetPassword(payload: ResetPasswordRequest): Promise<void> {
  await api.post('/auth/reset-password', payload)
}

// Reuses the same refresh flow the interceptor uses, so a silent session-restore on app
// load and a 401-triggered refresh never race each other on two different code paths.
export async function silentRefresh(): Promise<LoginResponse['accessToken']> {
  return refreshAccessToken()
}

export async function logout(): Promise<void> {
  try {
    // The X-XSRF-TOKEN header is attached automatically by the shared api interceptor
    // (only when the XSRF-TOKEN cookie has a value — A2 behavior).
    await api.post('/auth/logout', null)
  } finally {
    setAccessToken(null)
  }
}
