import { authRequest } from './http'
import { clearTokens, loadTokens, saveTokens } from '../auth/tokenStore'
import type { LoginRequest, SignupRequest, SignupResponse, TokenResponse } from '../types/api'

export function signup(request: SignupRequest) {
  return authRequest<SignupResponse>('/auth/signup', { method: 'POST', body: request })
}

export async function login(request: LoginRequest) {
  const tokens = await authRequest<TokenResponse>('/auth/login', { method: 'POST', body: request })
  saveTokens(tokens)
  return tokens
}

export async function logout() {
  const tokens = loadTokens()
  clearTokens()
  if (!tokens) return
  try {
    await authRequest<void>('/auth/logout', { method: 'POST', body: { refreshToken: tokens.refreshToken } })
  } catch {
    return
  }
}
