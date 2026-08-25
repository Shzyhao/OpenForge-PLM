import { get } from './client'
import type { PageData } from './material'

export interface LoginLog {
  id: number
  username: string | null
  success: number
  reason: string | null
  ip: string | null
  createdAt: string
}

export interface AuditLog {
  id: number
  operatorId: number | null
  action: string
  targetType: string
  targetId: string | null
  detail: string | null
  createdAt: string
}

export function fetchLoginLogs(params: { page?: number; pageSize?: number; username?: string }): Promise<PageData<LoginLog>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.username) q.set('username', params.username)
  return get<PageData<LoginLog>>(`/api/v1/security/login-logs?${q.toString()}`)
}

export function fetchAuditLogs(params: { page?: number; pageSize?: number; action?: string }): Promise<PageData<AuditLog>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.action) q.set('action', params.action)
  return get<PageData<AuditLog>>(`/api/v1/security/audit-logs?${q.toString()}`)
}
