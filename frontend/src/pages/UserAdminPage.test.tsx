import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserAdminPage from './UserAdminPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// v1.27 P4#24 首批：用户管理页列表/启停交互（mock API 层，无后端）
vi.mock('../api/user', () => ({
  fetchUsers: vi.fn(async () => ({
    list: [
      { id: 1, username: 'admin', displayName: '管理员', userType: 'NORMAL', status: 'ACTIVE', orgName: null },
      { id: 2, username: 'alice', displayName: '爱丽丝', userType: 'NORMAL', status: 'ACTIVE', orgName: '研发部' },
      { id: 3, username: 'bob', displayName: '鲍勃', userType: 'NORMAL', status: 'DISABLED', orgName: null },
    ],
    total: 3, page: 1, pageSize: 10,
  })),
  fetchRoles: vi.fn(async () => [{ id: 1, roleCode: 'ADMINS', roleName: '管理员' }]),
  enableUser: vi.fn(async () => undefined),
  disableUser: vi.fn(async () => undefined),
  batchUserStatus: vi.fn(async () => undefined),
  createUser: vi.fn(async () => ({ id: 3 })),
  deleteUser: vi.fn(async () => undefined),
  resetUserPassword: vi.fn(async () => 'Of@tmp123'),
  assignUserOrg: vi.fn(async () => undefined),
}))
vi.mock('../api/org', () => ({ fetchOrgTree: vi.fn(async () => []) }))

function permOf(userId = 1): PermContextValue {
  return {
    user: { id: userId, username: 'admin', displayName: 'admin', email: null, roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('UserAdminPage', () => {
  it('渲染用户列表与状态 Tag（启用/停用）', async () => {
    render(<PermContext.Provider value={permOf()}><UserAdminPage /></PermContext.Provider>)
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(screen.getAllByText('启用').length).toBeGreaterThan(0)   // 状态 Tag + bob 行启用按钮
    expect(screen.getAllByText('停用').length).toBeGreaterThan(0)
  })

  it('点停用调 disableUser；自己那一行停用按钮禁用', async () => {
    const { disableUser } = await import('../api/user')
    render(<PermContext.Provider value={permOf(1)}><UserAdminPage /></PermContext.Provider>)
    // alice（id=2，非当前用户）行有可点的停用按钮；admin 行禁用；正则兼容 antd 两字按钮自动空格
    const buttons = await screen.findAllByRole('button', { name: /^停\s*用$/ }, { timeout: 4000 })
    const clickable = buttons.find((b) => !b.hasAttribute('disabled'))
    expect(clickable).toBeTruthy()
    fireEvent.click(clickable!)
    await waitFor(() => {
      expect(disableUser).toHaveBeenCalledWith(2)
    })
  }, 15000)

  it('点启用调 enableUser', async () => {
    const { enableUser } = await import('../api/user')
    render(<PermContext.Provider value={permOf()}><UserAdminPage /></PermContext.Provider>)
    const enableButtons = await screen.findAllByRole('button', { name: /^启\s*用$/ }, { timeout: 4000 })
    fireEvent.click(enableButtons[0])
    await waitFor(() => {
      expect(enableUser).toHaveBeenCalledWith(3)   // bob（DISABLED 行的启用按钮）
    })
  }, 15000)
})
