import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, clearToken, get, post, put, saveToken, upload } from './client'

/** 统一响应体解析/错误传播/401 分流——API 层是所有页面的共同底座（R14 体检蓝本第一层）。 */
function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearToken()
})

describe('api client', () => {
  it('code=0 时解包 data 返回', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 0, message: 'ok', data: { id: 7 }, traceId: 't' })))
    await expect(get('/api/v1/things')).resolves.toEqual({ id: 7 })
  })

  it('code!=0 时抛 ApiError 携带后端消息与码', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      jsonResponse({ code: 2004, message: '无操作权限', data: null, traceId: 't' })))
    const err = await get('/api/v1/things').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(2004)
    expect((err as ApiError).message).toBe('无操作权限')
  })

  it('401+2001（会话失效）清除令牌并抛 2001', async () => {
    saveToken('stale-token')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 2001, message: '登录过期', data: null }, 401)))
    // client.ts 同步置 window.location.href='/login'（jsdom 导航未实现但不清 token 的副作用可断言）
    await expect(get('/api/v1/things')).rejects.toMatchObject({ code: 2001 })
    expect(localStorage.getItem('openforge_token')).toBeNull()
  })

  it('401+2002（凭证类错误）不清令牌、原样抛后端消息（2026-11-28 审查：登录/改密错误提示失效修复）', async () => {
    saveToken('valid-token')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 2002, message: '原密码不正确', data: null }, 401)))
    await expect(put('/api/v1/users/me/password', { oldPassword: 'x', newPassword: 'y' }))
      .rejects.toMatchObject({ code: 2002, message: '原密码不正确' })
    expect(localStorage.getItem('openforge_token')).toBe('valid-token')
  })

  it('非 JSON 错误体（网关默认页）抛友好提示而非英文解析错误', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 502, json: async () => { throw new Error('Unexpected token') }, text: async () => '<html>502</html>' } as unknown as Response)))
    await expect(get('/api/v1/things')).rejects.toMatchObject({ code: -1, message: '网关暂时不可用，请稍后重试' })
  })

  it('错误体缺 code 字段（网关默认 JSON 体）抛兜底提示而非空白', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ status: 500, error: 'Internal' }, 500)))
    await expect(get('/api/v1/things')).rejects.toMatchObject({ code: -1, message: '服务内部错误，请稍后重试' })
  })

  it('upload 成功解包 data', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ code: 0, message: 'ok', data: { id: 3 }, traceId: 't' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(upload('/api/v1/docs/1/files', new FormData())).resolves.toEqual({ id: 3 })
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.method).toBe('POST')
    // multipart 不手工设 Content-Type（浏览器自动带 boundary）
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined()
  })

  it('POST 携带 JSON 体与 Authorization 头', async () => {
    saveToken('token-1')
    const fetchMock = vi.fn(async () => jsonResponse({ code: 0, message: 'ok', data: null, traceId: 't' }))
    vi.stubGlobal('fetch', fetchMock)
    await post('/api/v1/things', { name: 'x' })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/v1/things')
    expect(init.method).toBe('POST')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-1')
    expect(init.body).toBe(JSON.stringify({ name: 'x' }))
  })
})
