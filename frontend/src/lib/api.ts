import axios, { type InternalAxiosRequestConfig } from 'axios'

import { getCookie } from '@/lib/cookies'
import { resolveApiBaseUrl } from '@/lib/runtime-config'
import { randomUuid } from '@/lib/uuid'

// Gateway is the single origin the browser talks to; it routes /api/v1/{auth,matches,
// inventory,payments,notifications,bookings}/** to the right downstream service.
// Resolved once at module load, after /config.js has run — see runtime-config.ts for why the
// origin is no longer read straight off import.meta.env.
export const API_BASE_URL = resolveApiBaseUrl()

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
// when it is a well-formed UUID v4 and silently replaces anything else — which randomUuid's
// insecure-context fallback is written to satisfy.
const newCorrelationId = randomUuid

// CSRF is only required for cookie-authenticated endpoints (/auth/refresh, /auth/logout), and
// identity-service's SecurityConfig says why: those two are state-changing requests authenticated
// by a cookie the browser attaches on its own, which is exactly what a cross-site form can forge.
// A bearer-authenticated request cannot be forged that way and must not carry this header.
//
// The header goes out only when the XSRF-TOKEN cookie actually has a value, because it is an echo
// of that cookie — CookieCsrfTokenRepository compares the two — so before the server has written
// the cookie there is nothing to echo and an empty header would be rejected exactly as an absent
// one is.
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

/**
 * The access token to send, refreshing first when the in-memory one is gone — which it is
 * immediately after a page reload, while the HttpOnly refresh session is still good.
 *
 * <p>Callers that need a token *before* dispatching (rather than reacting to a 401) bypass the
 * response interceptor, and with it the interceptor's session-expired handling. Doing the refresh
 * inline here without that meant a customer whose session had actually expired got their request's
 * own generic error — on checkout, "Không thể tạo đơn đặt vé. Vui lòng thử lại." — and could
 * retry it forever, because nothing ever told AuthProvider the session was gone and no route
 * guard ever sent them to log in.
 */
export async function ensureAccessToken(): Promise<string> {
  const current = getAccessToken()
  if (current) return current
  try {
    return await refreshAccessToken()
  } catch (error) {
    // Same handling as the 401 path in the response interceptor above.
    setAccessToken(null)
    onSessionExpired?.()
    throw error
  }
}

export { refreshAccessToken }
