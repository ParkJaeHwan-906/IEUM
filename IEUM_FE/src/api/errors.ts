import type { ProblemDetail } from '../types/api'

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail | null

  constructor(status: number, problem: ProblemDetail | null, fallback: string) {
    super(problem?.detail ?? problem?.title ?? fallback)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof TypeError) return '서버에 연결할 수 없습니다. 서버가 켜져 있는지 확인해 주세요.'
  if (error instanceof Error) return error.message
  return '알 수 없는 오류가 발생했습니다.'
}
