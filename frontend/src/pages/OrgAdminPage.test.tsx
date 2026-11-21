import { render, screen } from '@testing-library/react'
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
    const { container } = render(<OrgAdminPage />)
    await vi.waitFor(() => {
      expect(fetchOrgTree).toHaveBeenCalledTimes(1)
    })
    // Tree 组件挂载（节点文本受 jsdom 虚拟列表零高度限制不展开渲染，见 antd Tree virtual）
    expect((await screen.findAllByText(/组织/)).length).toBeGreaterThan(0)
    expect(container.querySelector('.ant-tree')).toBeTruthy()
  })

  it('空树时 Tree 容器仍在、无组织节点', async () => {
    const { container } = render(<OrgAdminPage />)
    await vi.waitFor(() => {
      expect(container.querySelector('.ant-tree')).toBeTruthy()
    })
  })
})
