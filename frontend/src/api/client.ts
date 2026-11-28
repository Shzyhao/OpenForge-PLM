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

/** 网关/非标准错误体的兜底提示（2026-11-28 审查：网关默认错误体无 code/message 曾弹空白提示，非 JSON 体曾弹英文解析错误） */
const STATUS_HINTS: Record<number, string> = {
  401: '登录已过期，请重新登录',
  403: '无操作权限',
  404: '资源不存在',
  500: '服务内部错误，请稍后重试',
  502: '网关暂时不可用，请稍后重试',
  503: '服务暂时不可用，请稍后重试',
}

/** 安全解析统一响应体：空体/非 JSON/缺 code 字段（网关默认错误页）一律返回 null。
 *  优先 text()（真实 Response 语义），text 不可用时回退 json()（兼容测试 stub 的最小 mock）。 */
async function safePayload<T>(res: Response): Promise<ApiResponse<T> | null> {
  const parse = (raw: string | null): ApiResponse<T> | null => {
    if (!raw) return null
    try {
      const parsed: unknown = JSON.parse(raw)
      if (!parsed || typeof parsed !== 'object') return null
      if (typeof (parsed as ApiResponse<T>).code !== 'number') return null
      return parsed as ApiResponse<T>
    } catch {
      return null
    }
  }
  let raw: string | null = null
  try {
    raw = await res.text()
  } catch {
    raw = null
  }
  if (raw == null && typeof res.json === 'function') {
    try {
      const j = await res.json()
      raw = typeof j === 'string' ? j : JSON.stringify(j ?? '')
    } catch {
      raw = null
    }
  }
  return parse(raw)
}

/**
 * 401 分流（2026-11-28 审查实锤）：后端把 BAD_CREDENTIALS(2002)/ACCOUNT_DISABLED(2003)/TENANT_DISABLED(2007)
 * 也映射 HTTP 401，原「见 401 即清 token 跳登录」导致输错密码无提示、改密旧密码错被登出。
 * 仅会话失效（2001 或网关无标准体）才登出跳转；凭证类错误原样抛给页面提示。
 */
async function rejectWith401<T>(res: Response): Promise<never> {
  const body = await safePayload<T>(res)
  if (body && body.code !== 2001) {
    throw new ApiError(body.code, body.message || STATUS_HINTS[res.status] || '请求失败')
  }
  clearToken()
  window.location.href = '/login'
  throw new ApiError(2001, '登录已过期，请重新登录')
}

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
    return rejectWith401<T>(res)
  }
  const payload = await safePayload<T>(res)
  if (!payload) {
    throw new ApiError(-1, STATUS_HINTS[res.status] ?? `服务异常（HTTP ${res.status}），请稍后重试`)
  }
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message || STATUS_HINTS[res.status] || '请求失败')
  }
  return payload.data
}

export async function get<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}

export async function post<T>(path: string, body?: unknown, opts?: { timeoutMs?: number }): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
    ...(opts?.timeoutMs ? { signal: AbortSignal.timeout(opts.timeoutMs) } : {}),
  })
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

/**
 * multipart 上传统一收口（2026-11-28 审查实锤：doc/drawing 原为裸 fetch——无超时、401 被吞、
 * 非 JSON 体直接崩）。不手工设置 Content-Type（浏览器自动带 boundary）。
 */
export async function upload<T>(path: string, form: FormData, timeoutMs = 120_000): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken() ?? ''}` },
      body: form,
      signal: AbortSignal.timeout(timeoutMs),
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'TimeoutError') {
      throw new ApiError(-1, '上传超时，请检查网络或文件大小后重试')
    }
    throw e
  }
  if (res.status === 401) {
    return rejectWith401<T>(res)
  }
  const payload = await safePayload<T>(res)
  if (!payload) {
    throw new ApiError(-1, STATUS_HINTS[res.status] ?? `上传失败（HTTP ${res.status}）`)
  }
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message || '上传失败')
  }
  return payload.data
}

/** AI 对话超时：后端多供应商 60s/家串行，网关全局 130s clamp（v1.32 P1-7）——对齐 130s（原 30s 必超时） */
const AI_CHAT_TIMEOUT_MS = 130_000

/** AI 中台对话（离线模式返回降级标识回复） */
export async function aiChat(messages: { role: 'user' | 'assistant'; content: string }[]): Promise<{ reply: string; mode: string; model: string | null }> {
  return post('/api/v1/ai/chat', { messages }, { timeoutMs: AI_CHAT_TIMEOUT_MS })
}
