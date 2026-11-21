import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RoleAdminPage from './RoleAdminPage'

// R14 第四批（v1.28）：角色页列表/成员弹窗/角色表分页（v1.28 P3#20）
vi.mock('../api/user', () => ({
  fetchRoles: vi.fn(async () => []),
  fetchRoleMembers: vi.fn(async () => []),
  fetchRolePermissionIds: vi.fn(async () => []),
  fetchAllPermissions: vi.fn(async () => []),
  fetchUsers: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 50 })),
  addRoleMembers: vi.fn(async () => undefined),
  removeRoleMember: vi.fn(async () => undefined),
  saveRolePermissions: vi.fn(async () => undefined),
  createRole: vi.fn(async () => ({ id: 9 })),
  deleteRole: vi.fn(async () => undefined),
  updateRole: vi.fn(async () => undefined),
}))

const ROLE = { id: 1, roleCode: 'ADMINS', roleName: '管理员', description: null, builtin: 1 }

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RoleAdminPage', () => {
  it('列表渲染角色与内置 Tag；分页计数可见（v1.28 P3#20）', async () => {
    const { fetchRoles } = await import('../api/user')
    vi.mocked(fetchRoles).mockResolvedValueOnce([ROLE] as never)
    render(<RoleAdminPage />)
    expect(await screen.findByText('ADMINS')).toBeInTheDocument()
    expect(screen.getAllByText('内置').length).toBeGreaterThan(0)
    expect(screen.getByText('共 1 条')).toBeInTheDocument()
  })

  it('成员弹窗：打开加载成员与候选用户（搜索式选择就绪）', async () => {
    const { fetchRoles, fetchRoleMembers, fetchUsers } = await import('../api/user')
    vi.mocked(fetchRoles).mockResolvedValueOnce([ROLE] as never)
    vi.mocked(fetchRoleMembers).mockResolvedValueOnce([
      { id: 2, username: 'alice', displayName: '爱丽丝' },
    ] as never)
    vi.mocked(fetchUsers).mockResolvedValueOnce({
      list: [{ id: 3, username: 'bob', displayName: null, userType: 'NORMAL', status: 'ACTIVE', orgName: null }] as never,
      total: 1, page: 1, pageSize: 50,
    })
    render(<RoleAdminPage />)
    fireEvent.click(await screen.findByText('成员'))
    await waitFor(() => {
      expect(fetchRoleMembers).toHaveBeenCalledWith(1)
      expect(fetchUsers).toHaveBeenCalled()
    })
    expect(await screen.findByText(/爱丽丝/)).toBeInTheDocument()
  })
})
