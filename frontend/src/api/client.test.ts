import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, clearToken, get, post, saveToken } from './client'

/** 统一响应体解析/错误传播/401 登出跳转——API 层是所有页面的共同底座（R14 体检蓝本第一层）。 */
function jsonResponse(body: unknown, status = 200) {
  return { ok: status < 400, status, json: async () => body } as Response
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

  it('401 时清除令牌并抛 2001', async () => {
    saveToken('stale-token')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 2001, message: '登录过期', data: null }, 401)))
    // client.ts 同步置 window.location.href='/login'（jsdom 导航未实现但不清 token 的副作用可断言）
    await expect(get('/api/v1/things')).rejects.toMatchObject({ code: 2001 })
    expect(localStorage.getItem('openforge_token')).toBeNull()
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
