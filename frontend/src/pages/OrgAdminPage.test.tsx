import { render, screen } from '@testing-library/react'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrgAdminPage from './OrgAdminPage'

// R14 第四批（v1.28）：组织页树渲染/空态
vi.mock('../api/org', () => ({
  fetchOrgTree: vi.fn(async () => []),
  fetchOrgUsers: vi.fn(async () => []),
  createOrg: vi.fn(),
  updateOrg: vi.fn(),
  deleteOrg: vi.fn(async () => undefined),
}))
vi.mock('../api/user', () => ({
  fetchUsers: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 50 })),
  assignUserOrg: vi.fn(async () => undefined),
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

describe('OrgAdminPage', () => {
  it('加载组织树并挂载 Tree 容器（jsdom 虚拟列表零高度不渲染节点，断言数据面）', async () => {
    const { fetchOrgTree } = await import('../api/org')
    vi.mocked(fetchOrgTree).mockResolvedValueOnce([
      {
        id: 1, orgCode: 'HQ', orgName: '总部', parentId: null, sortOrder: 1,
        children: [
          { id: 2, orgCode: 'RD', orgName: '研发部', parentId: 1, sortOrder: 1, children: [] },
        ],
      },
    ] as never)
    const { container } = render(<PermContext.Provider value={permOf()}><OrgAdminPage /></PermContext.Provider>)
    await vi.waitFor(() => {
      expect(fetchOrgTree).toHaveBeenCalledTimes(1)
    })
    // Tree 组件挂载（节点文本受 jsdom 虚拟列表零高度限制不展开渲染，见 antd Tree virtual）
    expect((await screen.findAllByText(/组织/)).length).toBeGreaterThan(0)
    expect(container.querySelector('.ant-tree')).toBeTruthy()
  })

  it('空树时 Tree 容器仍在、无组织节点', async () => {
    const { container } = render(<PermContext.Provider value={permOf()}><OrgAdminPage /></PermContext.Provider>)
    await vi.waitFor(() => {
      expect(container.querySelector('.ant-tree')).toBeTruthy()
    })
  })
})
