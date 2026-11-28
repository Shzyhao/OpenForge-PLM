import { afterEach, describe, expect, it, vi } from 'vitest'
import { EVENT_OPTIONS, fetchNotifications, markNotificationRead, unsubscribeEvent } from '../api/notification'

/** 订阅元数据契约：与后端 NotifyService.SUBSCRIBABLE_EVENTS 对齐（新增可订阅事件须两侧同步）。 */
describe('notification api', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('EVENT_OPTIONS 覆盖五个发布类事件', () => {
    expect(EVENT_OPTIONS.map((o) => o.key).sort()).toEqual([
      'bom.published', 'doc.released', 'drawing.obsolete', 'drawing.released', 'part.released',
    ])
  })

  it('fetchNotifications 透传未读筛选与分页参数', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200,
      json: async () => ({ code: 0, message: 'ok', data: { list: [], total: 0, page: 2, pageSize: 20 }, traceId: 't' }),
    }))
    vi.stubGlobal('fetch', fetchMock)
    const page = await fetchNotifications({ page: 2, size: 20, unreadOnly: true })
    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toBe('/api/v1/notifications?unreadOnly=true&page=2&size=20')
    expect(page.total).toBe(0)
  })

  it('markNotificationRead 拼接单条已读路径', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => ({ code: 0, message: 'ok', data: null, traceId: 't' }),
    }))
    vi.stubGlobal('fetch', fetchMock)
    await markNotificationRead(42)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/v1/notifications/42/read')
    expect(init.method).toBe('POST')
  })

  it('unsubscribeEvent 走 DELETE', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => ({ code: 0, message: 'ok', data: null, traceId: 't' }),
    }))
    vi.stubGlobal('fetch', fetchMock)
    await unsubscribeEvent('doc.released')
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/v1/notifications/subscriptions/doc.released')
    expect(init.method).toBe('DELETE')
  })
})
