import { ApiError } from './errors'
import { API_BASE_URL, AUTH_BASE_URL } from './config'
import { clearTokens, loadTokens, saveTokens } from '../auth/tokenStore'
import type { ProblemDetail, TokenResponse } from '../types/api'

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  auth?: boolean
}

async function parseError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null
  try {
    problem = (await res.json()) as ProblemDetail
  } catch {
    problem = null
  }
  return new ApiError(res.status, problem, `요청에 실패했습니다 (${res.status})`)
}

async function send<T>(base: string, path: string, options: RequestOptions): Promise<T> {
  const headers: Record<string, string> = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.auth) {
    const tokens = loadTokens()
    if (tokens) headers.Authorization = `Bearer ${tokens.accessToken}`
  }
  const res = await fetch(`${base}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

let refreshing: Promise<boolean> | null = null

function tryRefresh(): Promise<boolean> {
  if (refreshing) return refreshing
  refreshing = (async () => {
    const tokens = loadTokens()
    if (!tokens) return false
    try {
      const next = await send<TokenResponse>(AUTH_BASE_URL, '/auth/refresh', {
        method: 'POST',
        body: { refreshToken: tokens.refreshToken },
      })
      saveTokens(next)
      return true
    } catch {
      clearTokens()
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

export function authRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return send<T>(AUTH_BASE_URL, path, options)
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const withAuth = { ...options, auth: options.auth ?? true }
  try {
    return await send<T>(API_BASE_URL, path, withAuth)
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && withAuth.auth && loadTokens()) {
      const ok = await tryRefresh()
      if (ok) return send<T>(API_BASE_URL, path, withAuth)
    }
    throw error
  }
}
