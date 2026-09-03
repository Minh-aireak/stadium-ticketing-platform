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

export const CORRELATION_ID_HEADER = 'X-Correlation-Id'

// The gateway already generates a correlation ID for every request and every service logs its
// lines under it; sending our own only means the browser knows the ID too, so a failed request
// can show the user the exact value to quote. CorrelationIdWebFilter trusts an inbound ID only
// when it is a well-formed UUID v4 and silently replaces anything else — which the fallback here
// has to satisfy as well, since crypto.randomUUID is undefined outside a secure context (the app
// served over plain http on a LAN IP, for instance).
function newCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
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
  // Skipped when the header is already set, so the 401-refresh retry below re-sends the original
  // request under its original ID instead of splitting one user action across two trails.
  if (!config.headers.get(CORRELATION_ID_HEADER)) {
    config.headers.set(CORRELATION_ID_HEADER, newCorrelationId())
  }
  // /auth/logout is cookie-authenticated (no bearer token), so it needs the CSRF header.
  // Other POST/PUT/DELETE endpoints stay bearer-authenticated and must NOT get this header.
  // .set(), not Object.assign: config.headers is an AxiosHeaders instance, and assigning own
  // properties onto it bypasses the name normalization every other write here goes through.
  if (config.url?.includes('/auth/logout')) {
    const csrfToken = getCookie('XSRF-TOKEN')
    if (csrfToken) {
      config.headers.set('X-XSRF-TOKEN', csrfToken)
    }
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
          // Bypasses the shared instance's interceptor (see above), so the header is set by hand.
          headers: { ...buildCsrfHeaders(), [CORRELATION_ID_HEADER]: newCorrelationId() },
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
