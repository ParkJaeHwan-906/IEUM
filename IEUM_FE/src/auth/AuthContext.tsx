import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { currentUser, saveTokens, subscribe, type AuthUser } from './tokenStore'
import { login as apiLogin, logout as apiLogout } from '../api/auth'
import { USE_MOCK } from '../api/config'
import type { LoginRequest, UserType } from '../types/api'

interface AuthContextValue {
  user: AuthUser | null
  isConsumer: boolean
  isOwner: boolean
  login: (request: LoginRequest) => Promise<void>
  logout: () => Promise<void>
  demoLogin: (role: Exclude<UserType, 'ADMIN'>) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function base64Url(value: string) {
  return btoa(unescape(encodeURIComponent(value))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function demoToken(role: Exclude<UserType, 'ADMIN'>) {
  const now = Math.floor(Date.now() / 1000)
  const header = base64Url(JSON.stringify({ alg: 'none', kid: 'demo' }))
  const payload = base64Url(
    JSON.stringify({
      iss: 'demo',
      sub: role === 'CONSUMER' ? 'demo-consumer' : 'demo-owner',
      jti: 'demo',
      iat: now,
      exp: now + 60 * 60 * 24,
      role,
      nickname: role === 'CONSUMER' ? '데모소비자' : '데모점주',
    }),
  )
  return `${header}.${payload}.`
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => currentUser())

  useEffect(() => subscribe(() => setUser(currentUser())), [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isConsumer: user?.role === 'CONSUMER',
      isOwner: user?.role === 'BUSINESS_OWNER',
      async login(request) {
        await apiLogin(request)
      },
      async logout() {
        await apiLogout()
      },
      demoLogin(role) {
        if (!USE_MOCK) return
        saveTokens({ accessToken: demoToken(role), refreshToken: 'demo' })
      },
    }),
    [user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
