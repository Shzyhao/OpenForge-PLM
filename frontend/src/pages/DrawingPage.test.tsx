import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DrawingPage from './DrawingPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第二批（v1.28）：图纸页列表/空态
vi.mock('../api/drawing', () => ({
  fetchDrawings: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  fetchDrawing: vi.fn(),
  fetchDrawingFiles: vi.fn(async () => []),
  fetchDrawingVersions: vi.fn(async () => []),
  fetchDrawingParts: vi.fn(async () => []),
  createDrawing: vi.fn(),
  deleteDrawing: vi.fn(async () => undefined),
  drawingAction: vi.fn(async () => undefined),
  uploadDrawingFile: vi.fn(),
  fetchDrawingReviewConfig: vi.fn(async () => ({ workflowReview: false })),
  downloadDrawingFile: vi.fn(async () => ({ blob: new Blob(), fileName: 'd.pdf' })),
  linkPart: vi.fn(async () => undefined),
  unlinkPart: vi.fn(async () => undefined),
}))
vi.mock('../api/material', () => ({
  fetchParts: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 50 })),
}))

function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: 'admin', email: null, roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

function renderPage() {
  return render(<PermContext.Provider value={permOf()}><DrawingPage /></PermContext.Provider>)
}

describe('DrawingPage', () => {
  it('列表渲染图号与状态 Tag（评审中/已发布）', async () => {
    const { fetchDrawings } = await import('../api/drawing')
    vi.mocked(fetchDrawings).mockResolvedValueOnce({
      list: [
        { id: 1, drawingNumber: 'DWG-100', title: '壳体', versionMajor: 'A', versionMinor: 0, lifecycleState: 'REVIEWING', checkedOutBy: null },
        { id: 2, drawingNumber: 'DWG-101', title: '端盖', versionMajor: 'A', versionMinor: 2, lifecycleState: 'RELEASED', checkedOutBy: null },
      ] as never,
      total: 2, page: 1, pageSize: 10,
    })
    renderPage()
    expect(await screen.findByText('DWG-100')).toBeInTheDocument()
    expect(screen.getByText('DWG-101')).toBeInTheDocument()
    expect(screen.getByText('评审中')).toBeInTheDocument()
    expect(screen.getByText('已发布')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    renderPage()
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })

  it('审签启用时隐藏「直接发布/驳回」并提示走待办（批三-B）', async () => {
    const { fetchDrawings, fetchDrawing, fetchDrawingFiles, fetchDrawingReviewConfig } = await import('../api/drawing')
    vi.mocked(fetchDrawingReviewConfig).mockResolvedValueOnce({ workflowReview: true })
    vi.mocked(fetchDrawings).mockResolvedValueOnce({
      list: [{ id: 9, drawingNumber: 'DW202611280004', title: 'review-flow-verify', lifecycleState: 'REVIEWING', versionMajor: 'A', versionMinor: 0, checkedOutBy: null }] as never,
      total: 1, page: 1, pageSize: 10,
    })
    vi.mocked(fetchDrawing).mockResolvedValueOnce({
      id: 9, drawingNumber: 'DW202611280004', title: 'review-flow-verify', lifecycleState: 'REVIEWING', versionMajor: 'A', versionMinor: 0, checkedOutBy: null,
    } as never)
    vi.mocked(fetchDrawingFiles).mockResolvedValueOnce([])
    vi.mocked(await import('../api/drawing')).fetchDrawingVersions.mockResolvedValueOnce([])
    vi.mocked(await import('../api/drawing')).fetchDrawingParts.mockResolvedValueOnce([])
    renderPage()
    fireEvent.click(await screen.findByText('review-flow-verify'))
    // 行内「详情」按钮打开抽屉
    const row = screen.getByText('review-flow-verify').closest('tr')
    const detailBtn = Array.from(row?.querySelectorAll('button') ?? []).find(b => (b.textContent || '').replace(/\s/g, '').includes('详情'))
    fireEvent.click(detailBtn!)
    await waitFor(() => expect(screen.getByText(/审签流程已启用/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /发\s*布/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /驳\s*回/ })).toBeNull()
  })

})
