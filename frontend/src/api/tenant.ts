import { get, post } from './client'

// ===== 租户管理 + 开通流水线（十轮收口：平台级操作） =====

export interface SysTenant {
  id: number
  tenantCode: string
  tenantName: string
  enabled: number
  remark: string | null
  createdAt: string | null
}

export function fetchTenants(): Promise<SysTenant[]> {
  return get<SysTenant[]>('/api/v1/tenants')
}

export function onboardTenant(body: {
  tenantCode: string; tenantName: string; remark?: string
  adminUsername: string; adminPassword: string; adminDisplayName?: string
}): Promise<{ tenant: SysTenant; adminUserId: number; adminUsername: string }> {
  return post('/api/v1/tenants/onboard', body)
}

export function toggleTenant(id: number, enable: boolean): Promise<void> {
  return post(`/api/v1/tenants/${id}/${enable ? 'enable' : 'disable'}`)
}
