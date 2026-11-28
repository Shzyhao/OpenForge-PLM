import { render, screen } from '@testing-library/react'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantAdminPage from './TenantAdminPage'

// R14 第四批（v1.28）：租户页列表/空态
vi.mock('../api/tenant', () => ({
  fetchTenants: vi.fn(async () => []),
  onboardTenant: vi.fn(),
  toggleTenant: vi.fn(async () => undefined),
}))

beforeEach(() => {
  vi.clearAllMocks()
})


// 写按钮权限门控（2026-11-28 审查批二残留）依赖 PermContext——测试注入全权限上下文保持原断言
function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: 'admin', email: null, roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

describe('TenantAdminPage', () => {
  it('列表渲染租户编码/名称', async () => {
    const { fetchTenants } = await import('../api/tenant')
    vi.mocked(fetchTenants).mockResolvedValueOnce([
      { id: 1, tenantCode: 'acme', tenantName: '艾克米', enabled: 1, remark: null, createdAt: null },
    ] as never)
    render(<PermContext.Provider value={permOf()}><TenantAdminPage /></PermContext.Provider>)
    expect(await screen.findByText('acme')).toBeInTheDocument()
    expect(screen.getByText('艾克米')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><TenantAdminPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
