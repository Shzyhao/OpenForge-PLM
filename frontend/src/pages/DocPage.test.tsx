import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DocPage from './DocPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// 检入判本人（2026-11-28 审查）依赖 PermContext 的当前用户——与 UserAdminPage.test 同款包裹
function permOf(userId = 1): PermContextValue {
  return {
    user: { id: userId, username: 'admin', displayName: 'admin', email: null, roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

// R14 第二批（v1.28）：文档页列表/空态
vi.mock('../api/doc', () => ({
  fetchDocs: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  createDoc: vi.fn(),
  checkIn: vi.fn(async () => undefined),
  checkOut: vi.fn(async () => undefined),
  uploadDocFile: vi.fn(),
  downloadDocFile: vi.fn(async () => ({ blob: new Blob(), fileName: 'f.txt' })),
  fetchDocFiles: vi.fn(async () => []),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DocPage', () => {
  it('列表渲染文档编号/类型中文标签/状态', async () => {
    const { fetchDocs } = await import('../api/doc')
    vi.mocked(fetchDocs).mockResolvedValueOnce({
      list: [
        { id: 1, docNumber: 'DOC-001', title: '设计规范', docType: 'SPEC', lifecycleState: 'RELEASED', versionMinor: 2, checkedOutBy: null, checkedOutAt: null },
      ] as never,
      total: 1, page: 1, pageSize: 10,
    })
    render(<PermContext.Provider value={permOf()}><DocPage /></PermContext.Provider>)
    expect(await screen.findByText('DOC-001')).toBeInTheDocument()
    expect(screen.getByText('规格书')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><DocPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
