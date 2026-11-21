import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModuleAdminPage from './ModuleAdminPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第四批（v1.28）：模块注册表页列表/空态
vi.mock('../api/modules', () => ({
  fetchModules: vi.fn(async () => []),
  enableModule: vi.fn(async () => undefined),
  disableModule: vi.fn(async () => undefined),
}))

function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: 'admin', roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ModuleAdminPage', () => {
  it('列表渲染模块 key/显示名/类型', async () => {
    const { fetchModules } = await import('../api/modules')
    vi.mocked(fetchModules).mockResolvedValueOnce([
      {
        id: 1, moduleKey: 'auth', moduleType: 'KERNEL', displayName: '认证与用户', version: '0.1.0',
        status: 'ENABLED', routes: '["/api/v1/auth"]', menu: null, dependencies: '[]',
        serviceUri: 'http://localhost:8081', heartbeatAt: '2026-11-21T10:00:00', registeredAt: '2026-01-01T00:00:00',
      },
    ] as never)
    render(<PermContext.Provider value={permOf()}><ModuleAdminPage /></PermContext.Provider>)
    expect(await screen.findByText('auth')).toBeInTheDocument()
    expect(screen.getByText('认证与用户')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><ModuleAdminPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
