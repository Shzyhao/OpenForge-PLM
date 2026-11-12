import { del, get, post } from './client'

export interface NotifyMessage {
  id: number
  userId: number
  eventType: string
  bizType: string | null
  bizId: number | null
  title: string
  content: string | null
  readFlag: number
  createdAt: string
}

export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export function fetchNotifications(params: { unreadOnly?: boolean; page?: number; size?: number } = {}): Promise<PageData<NotifyMessage>> {
  const q = new URLSearchParams()
  if (params.unreadOnly) q.set('unreadOnly', 'true')
  q.set('page', String(params.page ?? 1))
  q.set('size', String(params.size ?? 20))
  return get<PageData<NotifyMessage>>(`/api/v1/notifications?${q.toString()}`)
}

export function fetchUnreadCount(): Promise<number> {
  return get<{ count: number }>('/api/v1/notifications/unread-count').then((d) => d.count)
}

export function markNotificationRead(id: number): Promise<void> {
  return post(`/api/v1/notifications/${id}/read`)
}

export function markAllNotificationsRead(): Promise<void> {
  return post('/api/v1/notifications/read-all')
}

// ===== 订阅管理（v1.24：发布类事件通知设置）=====

export interface NotifySubscription {
  id: number
  userId: number
  eventKey: string
}

/** 可订阅事件元数据（与后端 NotifyService.SUBSCRIBABLE_EVENTS 对齐） */
export const EVENT_OPTIONS: { key: string; label: string; desc: string }[] = [
  { key: 'part.released', label: '物料发布', desc: '物料发布生效时提醒' },
  { key: 'bom.published', label: 'BOM 发布', desc: 'BOM 发布生效时提醒' },
  { key: 'doc.released', label: '文档发布', desc: '文档新版本发布时提醒' },
  { key: 'drawing.released', label: '图纸发布', desc: '图纸发布时提醒' },
  { key: 'drawing.obsolete', label: '图纸作废', desc: '图纸作废时提醒' },
]

export function fetchSubscriptions(): Promise<NotifySubscription[]> {
  return get<NotifySubscription[]>('/api/v1/notifications/subscriptions')
}

export function subscribeEvent(eventKey: string): Promise<void> {
  return post(`/api/v1/notifications/subscriptions/${eventKey}`)
}

export function unsubscribeEvent(eventKey: string): Promise<void> {
  return del(`/api/v1/notifications/subscriptions/${eventKey}`)
}
