import { get, post, put, del } from './client'
import type { PageData } from './material'

// ===== 当前用户（F1 扩展） =====

export interface UserInfo {
  id: number
  username: string
  displayName: string
  roles: string[]
  menus: string[]
  permissions: string[]
  userType: string
}

export function fetchCurrentUser(): Promise<UserInfo> {
  return get<UserInfo>('/api/v1/users/me')
}

export function changeMyPassword(oldPassword: string, newPassword: string): Promise<void> {
  return put('/api/v1/users/me/password', { oldPassword, newPassword })
}

// ===== 用户管理（D 组） =====

export interface AdminUser {
  id: number
  username: string
  displayName: string | null
  email: string | null
  status: string
  userType: string
  orgId: number | null
}

export function fetchUsers(params: { page?: number; pageSize?: number; username?: string; roleId?: number; status?: string }): Promise<PageData<AdminUser>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.username) q.set('username', params.username)
  if (params.roleId) q.set('roleId', String(params.roleId))
  if (params.status) q.set('status', params.status)
  return get<PageData<AdminUser>>(`/api/v1/users?${q.toString()}`)
}

export function createUser(body: { username: string; password: string; displayName?: string; email?: string; roleIds?: number[] }): Promise<AdminUser> {
  return post<AdminUser>('/api/v1/users', body)
}

export function enableUser(id: number): Promise<AdminUser> { return post<AdminUser>(`/api/v1/users/${id}/enable`) }
export function disableUser(id: number): Promise<AdminUser> { return post<AdminUser>(`/api/v1/users/${id}/disable`) }
export function deleteUser(id: number): Promise<void> { return del(`/api/v1/users/${id}`) }
export function resetUserPassword(id: number, password: string): Promise<void> {
  return post(`/api/v1/users/${id}/reset-password`, { password })
}
export function assignUserRoles(userId: number, roleIds: number[]): Promise<void> {
  return put(`/api/v1/roles/users/${userId}`, { roleIds })
}

// ===== 角色管理（B 组） =====

export interface Role {
  id: number
  roleCode: string
  roleName: string
  builtin: number
  description: string | null
  enabled: number
}

export function fetchRoles(): Promise<Role[]> {
  return get<Role[]>('/api/v1/roles')
}

export function createRole(roleCode: string, roleName: string): Promise<Role> {
  return post<Role>('/api/v1/roles', { roleCode, roleName })
}

export function updateRole(id: number, roleName: string, description?: string): Promise<Role> {
  return put<Role>(`/api/v1/roles/${id}`, { roleName, description })
}

export function deleteRole(id: number): Promise<void> {
  return del(`/api/v1/roles/${id}`)
}

export function fetchRoleMembers(id: number): Promise<AdminUser[]> {
  return get<AdminUser[]>(`/api/v1/roles/${id}/members`)
}

export function addRoleMembers(id: number, userIds: number[]): Promise<void> {
  return post(`/api/v1/roles/${id}/members`, { userIds })
}

export function removeRoleMember(id: number, userId: number): Promise<void> {
  return del(`/api/v1/roles/${id}/members/${userId}`)
}

// ===== 权限树与矩阵（C 组） =====

export interface PermNode {
  id: number
  permCode: string
  permName: string
  permType: string
  description: string | null
  sortOrder: number
}

export interface PermissionTree {
  menus: PermNode[]
  operations: PermNode[]
}

export function fetchPermissionTree(): Promise<PermissionTree> {
  return get<PermissionTree>('/api/v1/permissions/tree')
}

export function fetchRolePermissionIds(id: number): Promise<string[]> {
  return get<string[]>(`/api/v1/roles/${id}/permissions`)
}

export function fetchAllPermissions(): Promise<PermNode[]> {
  return get<PermNode[]>('/api/v1/permissions')
}

export function saveRolePermissions(id: number, permissionIds: number[]): Promise<void> {
  return put(`/api/v1/roles/${id}/permissions`, { permissionIds })
}
