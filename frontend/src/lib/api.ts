import axios, { type InternalAxiosRequestConfig } from 'axios'

import { getCookie } from '@/lib/cookies'

// Gateway is the single origin the browser talks to; it routes /api/v1/{auth,matches,
// inventory,payments,notifications,bookings}/** to the right downstream service.
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'

// Access token lives in memory only (never localStorage/sessionStorage) — mirrors the
// backend's security model, where the refresh token is an HttpOnly cookie specifically
// so an XSS payload can't read either token.
let accessToken: string | null = null
let onSessionExpired: (() => void) | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function getAccessToken() {
  return accessToken
}

export function registerSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler
}

// CSRF is only required for cookie-authenticated endpoints (/auth/refresh, /auth/logout).
// Per A2, the X-XSRF-TOKEN header is only sent when the XSRF-TOKEN cookie actually has a
// value — if the cookie is missing, the header is omitted entirely.
function buildCsrfHeaders(): Record<string, string> {
  const token = getCookie('XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

export const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
})

api.interceptors.request.use((config) => {
  if (accessToken && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  // /auth/logout is cookie-authenticated (no bearer token), so it needs the CSRF header.
  // Other POST/PUT/DELETE endpoints stay bearer-authenticated and must NOT get this header.
  if (config.url?.includes('/auth/logout')) {
    Object.assign(config.headers, buildCsrfHeaders())
  }
  return config
})

interface RetryableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean
}

let refreshPromise: Promise<string> | null = null

// /auth/refresh is cookie-authenticated (see identity-service SecurityConfig): it needs the
// CSRF header, not a bearer token, and must bypass this same interceptor to avoid recursion —
// so it goes through a bare axios call, not the shared `api` instance.
function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = axios
      .post<{ accessToken: string }>(
        `${API_BASE_URL}/auth/refresh`,
        null,
        {
          withCredentials: true,
          headers: buildCsrfHeaders(),
        },
      )
      .then((res) => {
        setAccessToken(res.data.accessToken)
        return res.data.accessToken
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config as RetryableConfig | undefined
    const isAuthEndpoint = config?.url?.includes('/auth/')

    if (error.response?.status === 401 && config && !config._retried && !isAuthEndpoint) {
      config._retried = true
      try {
        const newToken = await refreshAccessToken()
        config.headers.Authorization = `Bearer ${newToken}`
        return api(config)
      } catch {
        setAccessToken(null)
        onSessionExpired?.()
      }
    }

    return Promise.reject(error)
  },
)

export { refreshAccessToken }
