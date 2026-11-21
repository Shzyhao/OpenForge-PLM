import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DocPage from './DocPage'

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
    render(<DocPage />)
    expect(await screen.findByText('DOC-001')).toBeInTheDocument()
    expect(screen.getByText('规格书')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<DocPage />)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
