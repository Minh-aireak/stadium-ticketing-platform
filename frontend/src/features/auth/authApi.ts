import { api, refreshAccessToken, setAccessToken } from '@/lib/api'
import { getCookie } from '@/lib/cookies'
import type { LoginRequest, LoginResponse, RegisterRequest, RegisterResponse } from './types'

export async function login(payload: LoginRequest): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login', payload)
  setAccessToken(data.accessToken)
  return data
}

export async function register(payload: RegisterRequest): Promise<RegisterResponse> {
  const { data } = await api.post<RegisterResponse>('/auth/register', payload)
  return data
}

// Reuses the same refresh flow the interceptor uses, so a silent session-restore on app
// load and a 401-triggered refresh never race each other on two different code paths.
export async function silentRefresh(): Promise<LoginResponse['accessToken']> {
  return refreshAccessToken()
}

export async function logout(): Promise<void> {
  try {
    await api.post(
      '/auth/logout',
      null,
      { headers: { 'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') ?? '' } },
    )
  } finally {
    setAccessToken(null)
  }
}
