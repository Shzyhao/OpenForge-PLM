import { get, post } from './client'

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
