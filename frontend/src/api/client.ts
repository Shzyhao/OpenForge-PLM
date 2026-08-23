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

export const TOKEN_KEY = 'openforge_token'

export function saveToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...init.headers },
  })
  if (res.status === 401) {
    clearToken()
    window.location.href = '/login'
    throw new ApiError(2001, '登录已过期，请重新登录')
  }
  const payload: ApiResponse<T> = await res.json()
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message)
  }
  return payload.data
}

export async function get<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}

export async function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
}

export async function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
}
