import { render, screen } from '@testing-library/react'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgePage from './KnowledgePage'

// R14 第五批（v1.28）：知识库页列表/空态
vi.mock('../api/knowledge', () => ({
  fetchKnowledge: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  searchKnowledge: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  createKnowledge: vi.fn(),
  feedbackKnowledge: vi.fn(async () => undefined),
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

describe('KnowledgePage', () => {
  it('列表渲染知识条目标题与摘要', async () => {
    const { fetchKnowledge } = await import('../api/knowledge')
    vi.mocked(fetchKnowledge).mockResolvedValueOnce({
      list: [
        { id: 1, title: '法兰选型指南', content: '正文', summary: '按压力等级选型', sourceType: 'MANUAL', tags: '设计', qualityScore: 80, usageCount: 3, createdAt: '2026-11-01' },
      ] as never,
      total: 1, page: 1, pageSize: 10,
    })
    render(<PermContext.Provider value={permOf()}><KnowledgePage /></PermContext.Provider>)
    expect(await screen.findByText('法兰选型指南')).toBeInTheDocument()
    expect(fetchKnowledge).toHaveBeenCalled()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><KnowledgePage /></PermContext.Provider>)
    expect(await screen.findAllByText(/暂无|No data/)).not.toHaveLength(0)
  })
})
