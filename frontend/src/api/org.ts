import { get, post, put, del } from './client'

// ===== 组织架构管理（十轮收口：后端 OrgController 全量接线） =====

export interface OrgNode {
  id: number
  orgCode: string
  orgName: string
  parentId: number | null
  sortOrder: number
  children: OrgNode[]
}

export interface OrgUser {
  id: number
  username: string
  displayName: string | null
  orgId: number | null
}

export function fetchOrgTree(): Promise<OrgNode[]> {
  return get<OrgNode[]>('/api/v1/orgs/tree')
}

export function createOrg(body: { orgCode: string; orgName: string; parentId?: number | null; sortOrder?: number }): Promise<unknown> {
  return post('/api/v1/orgs', body)
}

export function updateOrg(id: number, body: { orgName?: string; sortOrder?: number }): Promise<unknown> {
  return put(`/api/v1/orgs/${id}`, body)
}

export function moveOrg(id: number, parentId: number | null): Promise<void> {
  return put(`/api/v1/orgs/${id}/parent`, { parentId })
}

export function deleteOrg(id: number): Promise<void> {
  return del(`/api/v1/orgs/${id}`)
}

export function fetchOrgUsers(id: number, includeChildren = false): Promise<OrgUser[]> {
  return get<OrgUser[]>(`/api/v1/orgs/${id}/users?includeChildren=${includeChildren}`)
}
