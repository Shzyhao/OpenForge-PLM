import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkflowDefsPage from './WorkflowDefsPage'

// R14 第三批（v1.28）：流程定义页列表/空态
vi.mock('../api/workflow', () => ({
  fetchDefs: vi.fn(async () => []),
  deployDef: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('WorkflowDefsPage', () => {
  it('列表渲染流程定义 key/名称/版本', async () => {
    const { fetchDefs } = await import('../api/workflow')
    vi.mocked(fetchDefs).mockResolvedValueOnce([
      { id: 1, defKey: 'ecr-review', name: '变更评审', version: 3, status: 'DEPLOYED', definition: '{}' },
    ] as never)
    render(<WorkflowDefsPage />)
    expect(await screen.findByText('ecr-review')).toBeInTheDocument()
    expect(screen.getByText('变更评审')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<WorkflowDefsPage />)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
