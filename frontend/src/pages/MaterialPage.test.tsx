import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import MaterialPage from './MaterialPage'

// R14 第二批（v1.28）：物料页列表/详情抽屉（含关联图纸）
vi.mock('../api/material', () => ({
  PART_STATE_LABELS: { DRAFT: { label: '草稿', color: 'default' }, RELEASED: { label: '已发布', color: 'green' } },
  PART_TYPE_LABELS: { MADE: '自制件' },
  fetchParts: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  fetchCategoryTree: vi.fn(async () => []),
  createPart: vi.fn(),
  partAction: vi.fn(async () => undefined),
}))
vi.mock('../api/drawing', () => ({ fetchDrawingsByPart: vi.fn(async () => []) }))
vi.mock('../api/client', () => ({ ApiError: class extends Error { constructor(public code: number, m: string) { super(m) } } }))

beforeEach(() => {
  vi.clearAllMocks()
})

function renderPage() {
  return render(<MemoryRouter><MaterialPage /></MemoryRouter>)
}

describe('MaterialPage', () => {
  it('列表渲染物料编号与状态 Tag', async () => {
    const { fetchParts } = await import('../api/material')
    vi.mocked(fetchParts).mockResolvedValueOnce({
      list: [
        { id: 1, partNumber: 'P-001', name: '法兰盘', nameEn: null, type: 'MADE', categoryId: 1, attrs: null, unit: '件', lifecycleState: 'RELEASED', version: 'A/1' },
        { id: 2, partNumber: 'P-002', name: '垫片', nameEn: null, type: 'MADE', categoryId: 1, attrs: null, unit: null, lifecycleState: 'DRAFT', version: 'A/0' },
      ] as never, total: 2, page: 1, pageSize: 10,
    })
    renderPage()
    expect(await screen.findByText('P-001')).toBeInTheDocument()
    expect(screen.getByText('P-002')).toBeInTheDocument()
    expect(screen.getByText('已发布')).toBeInTheDocument()
    expect(screen.getByText('草稿')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    renderPage()
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })

  it('详情抽屉：基础信息 + 关联图纸反查（v1.28 P3#18）', async () => {
    const { fetchParts } = await import('../api/material')
    const { fetchDrawingsByPart } = await import('../api/drawing')
    vi.mocked(fetchParts).mockResolvedValueOnce({
      list: [
        { id: 5, partNumber: 'P-005', name: '支架', nameEn: null, type: 'MADE', categoryId: 1, attrs: null, unit: '件', lifecycleState: 'RELEASED', version: 'A/2' },
      ] as never, total: 1, page: 1, pageSize: 10,
    })
    vi.mocked(fetchDrawingsByPart).mockResolvedValueOnce([
      { drawingId: 9, drawingNumber: 'DWG-01', title: '支架图纸', version: 'A/1', lifecycleState: 'RELEASED', role: 'MAIN' },
    ])
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /^详\s*情$/ }, { timeout: 4000 }))
    expect(await screen.findByText('关联图纸（1）', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(await screen.findByText('DWG-01', {}, { timeout: 4000 })).toBeInTheDocument()
    await waitFor(() => {
      expect(fetchDrawingsByPart).toHaveBeenCalledWith(5)
    }, { timeout: 4000 })
  }, 15000)
})
