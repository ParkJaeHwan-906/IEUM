import type { TokenResponse, UserType } from '../types/api'

const KEY = 'ieum.tokens'

export interface AuthUser {
  uid: string
  role: UserType
  nickname: string
  exp: number
}

export interface StoredTokens {
  accessToken: string
  refreshToken: string
}

type Listener = () => void
const listeners = new Set<Listener>()

function notify() {
  listeners.forEach((listener) => listener())
}

export function subscribe(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function loadTokens(): StoredTokens | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as StoredTokens) : null
  } catch {
    return null
  }
}

export function saveTokens(tokens: TokenResponse | StoredTokens) {
  const stored: StoredTokens = { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken }
  try {
    localStorage.setItem(KEY, JSON.stringify(stored))
  } catch {
    return
  }
  notify()
}

export function clearTokens() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    return
  }
  notify()
}

export function decodeUser(accessToken: string): AuthUser | null {
  try {
    const payload = accessToken.split('.')[1]
    const json = decodeURIComponent(
      atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
        .split('')
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    )
    const claims = JSON.parse(json) as { sub: string; role: UserType; nickname: string; exp: number }
    return { uid: claims.sub, role: claims.role, nickname: claims.nickname, exp: claims.exp }
  } catch {
    return null
  }
}

export function currentUser(): AuthUser | null {
  const tokens = loadTokens()
  return tokens ? decodeUser(tokens.accessToken) : null
}
