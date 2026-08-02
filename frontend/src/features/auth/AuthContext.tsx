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
        await authApi.logout()
        setUser(null)
        setStatus('anonymous')
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
