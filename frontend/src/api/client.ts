/** 与后端 openforge-common 的统一响应体对应 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

export class ApiError extends Error {
  constructor(public code: number, message: string) {
    super(message)
  }
}

export async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const payload: ApiResponse<T> = await res.json()
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message)
  }
  return payload.data
}

export const TOKEN_KEY = 'openforge_token'

export function saveToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
