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
export const PASSWORD_STATUS_KEY = 'openforge_password_status'

export function saveToken(token: string, passwordStatus?: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  if (passwordStatus) {
    localStorage.setItem(PASSWORD_STATUS_KEY, passwordStatus)
  } else {
    localStorage.removeItem(PASSWORD_STATUS_KEY)
  }
}

export function getPasswordStatus(): string | null {
  return localStorage.getItem(PASSWORD_STATUS_KEY)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(PASSWORD_STATUS_KEY)
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 请求超时（v1.27 P1#5）：读类 15s / 写类 30s——后端假死时浏览器侧不再无限挂起，401 语义不变 */
const GET_TIMEOUT_MS = 15_000
const WRITE_TIMEOUT_MS = 30_000

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const timeout = init.signal ?? AbortSignal.timeout(init.method === 'GET' ? GET_TIMEOUT_MS : WRITE_TIMEOUT_MS)
  let res: Response
  try {
    res = await fetch(path, {
      ...init,
      signal: timeout,
      headers: { 'Content-Type': 'application/json', ...authHeaders(), ...init.headers },
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'TimeoutError') {
      throw new ApiError(-1, '请求超时，请稍后重试')
    }
    throw e
  }
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

export async function del<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

export async function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
}

export async function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) })
}

/** AI 中台对话（离线模式返回降级标识回复） */
export async function aiChat(messages: { role: 'user' | 'assistant'; content: string }[]): Promise<{ reply: string; mode: string; model: string | null }> {
  return post('/api/v1/ai/chat', { messages })
}
