import { render, screen } from '@testing-library/react'
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

describe('KnowledgePage', () => {
  it('列表渲染知识条目标题与摘要', async () => {
    const { fetchKnowledge } = await import('../api/knowledge')
    vi.mocked(fetchKnowledge).mockResolvedValueOnce({
      list: [
        { id: 1, title: '法兰选型指南', content: '正文', summary: '按压力等级选型', sourceType: 'MANUAL', tags: '设计', qualityScore: 80, usageCount: 3, createdAt: '2026-11-01' },
      ] as never,
      total: 1, page: 1, pageSize: 10,
    })
    render(<KnowledgePage />)
    expect(await screen.findByText('法兰选型指南')).toBeInTheDocument()
    expect(fetchKnowledge).toHaveBeenCalled()
  })

  it('空列表展示空态', async () => {
    render(<KnowledgePage />)
    expect(await screen.findAllByText(/暂无|No data/)).not.toHaveLength(0)
  })
})
