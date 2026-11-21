import { render, screen } from '@testing-library/react'
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

describe('TenantAdminPage', () => {
  it('列表渲染租户编码/名称', async () => {
    const { fetchTenants } = await import('../api/tenant')
    vi.mocked(fetchTenants).mockResolvedValueOnce([
      { id: 1, tenantCode: 'acme', tenantName: '艾克米', enabled: 1, remark: null, createdAt: null },
    ] as never)
    render(<TenantAdminPage />)
    expect(await screen.findByText('acme')).toBeInTheDocument()
    expect(screen.getByText('艾克米')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<TenantAdminPage />)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
