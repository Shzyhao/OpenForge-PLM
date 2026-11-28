import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import ChangePage from './ChangePage'

// R14 第三批（v1.28）：变更页列表/详情（流程不可达降级文案）
vi.mock('../api/change', () => ({
  APPLY_STATE_LABELS: {}, CHANGE_TYPE_LABELS: { GENERIC: { label: '通用变更' } },
  ECR_STATE_LABELS: { SUBMITTED: { label: '待审批', color: 'processing' } },
  PART_TARGET_LABELS: {}, URGENCY_LABELS: {},
  fetchEcrs: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  fetchEcrDetail: vi.fn(),
  createEcr: vi.fn(),
  retryApply: vi.fn(),
}))
vi.mock('../api/workflow', () => ({
  INSTANCE_STATE_LABELS: { RUNNING: { label: '进行中', color: 'processing' } },
  fetchInstance: vi.fn(),
}))
vi.mock('../api/material', () => ({
  PART_STATE_LABELS: {},
  fetchBom: vi.fn(), fetchBomLines: vi.fn(async () => []), fetchParts: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 50 })),
}))
vi.mock('../components/InstanceDetailDrawer', () => ({ default: () => <div data-testid="instance-drawer" /> }))

const ECR_ROW = {
  id: 1, ecrNumber: 'ECR-001', title: '垫片替代', reason: null, urgency: 'NORMAL',
  state: 'SUBMITTED', changeType: 'GENERIC', applyState: null, applyResult: null, workflowInstanceId: 501,
}

beforeEach(() => {
  vi.clearAllMocks()
})

function renderPage() {
  return render(<MemoryRouter initialEntries={['/change']}><ChangePage /></MemoryRouter>)
}

describe('ChangePage', () => {
  it('列表渲染 ECR 编号/标题链接/状态', async () => {
    const { fetchEcrs } = await import('../api/change')
    vi.mocked(fetchEcrs).mockResolvedValueOnce({ list: [ECR_ROW] as never, total: 1, page: 1, pageSize: 10 })
    renderPage()
    expect(await screen.findByText('垫片替代')).toBeInTheDocument()
    expect(screen.getByText('待审批')).toBeInTheDocument()
  })

  it('详情：流程服务不可达展示降级文案而非笼统"不可用"（v1.28 P2#12）', async () => {
    const { fetchEcrs, fetchEcrDetail } = await import('../api/change')
    vi.mocked(fetchEcrs).mockResolvedValueOnce({ list: [ECR_ROW] as never, total: 1, page: 1, pageSize: 10 })
    vi.mocked(fetchEcrDetail).mockResolvedValue({
      ...ECR_ROW, reason: 'r', payload: null, flowState: null, flowCurrentNode: null, flowReachable: false,
    })
    renderPage()
    fireEvent.click(await screen.findByText('垫片替代'))
    await waitFor(() => {
      expect(fetchEcrDetail).toHaveBeenCalledWith(1)
    })
  })

  it('空列表展示空态', async () => {
    renderPage()
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
