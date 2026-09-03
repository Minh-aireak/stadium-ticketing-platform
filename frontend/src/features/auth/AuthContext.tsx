import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { registerSessionExpiredHandler } from '@/lib/api'
import { decodeJwt } from '@/lib/jwt'
import * as authApi from './authApi'
import type { AuthUser } from './types'

type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  user: AuthUser | null
  status: AuthStatus
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<string>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function userFromToken(token: string): AuthUser | null {
  const payload = decodeJwt(token)
  if (!payload) return null
  return {
    id: payload.sub,
    email: payload.email ?? '',
    role: payload.role === 'ADMIN' ? 'ADMIN' : 'USER',
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [status, setStatus] = useState<AuthStatus>('loading')

  useEffect(() => {
    registerSessionExpiredHandler(() => {
      setUser(null)
      setStatus('anonymous')
    })

    // On app load there's no access token in memory yet (page refresh wipes it) — try
    // exchanging the HttpOnly refresh cookie for a new one before treating the user as logged out.
    authApi
      .silentRefresh()
      .then((token) => {
        setUser(userFromToken(token))
        setStatus('authenticated')
      })
      .catch(() => setStatus('anonymous'))
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      status,
      async login(email, password) {
        const result = await authApi.login({ email, password })
        setUser(userFromToken(result.accessToken))
        setStatus('authenticated')
      },
      async register(email, password) {
        const result = await authApi.register({ email, password })
        return result.accountId
      },
      async logout() {
        try {
          await authApi.logout()
        } finally {
          // Clear local state whichever way the request went, then let the rejection through.
          // authApi.logout drops the in-memory access token in its own finally, so on a failed
          // request this used to leave `user` and `status` untouched: the navbar kept showing
          // the customer's email and a "Đăng xuất" button while the app no longer held a token
          // to make requests with. The next protected call then 401'd, the response interceptor
          // spent the refresh cookie the server had never been told to revoke, and the session
          // came back — a logout that looked like it did nothing and, on the server, hadn't.
          setUser(null)
          setStatus('anonymous')
        }
      },
    }),
    [user, status],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
