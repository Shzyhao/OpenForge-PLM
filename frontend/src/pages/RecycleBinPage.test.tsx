import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RecycleBinPage from './RecycleBinPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 扩面（v1.25）：回收站七域 Tab + 恢复/彻底删除动作（mock API 层）
vi.mock('../api/recycle', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/recycle')>()
  return {
    ...actual,
    fetchTrashedParts: vi.fn(async () => [{ id: 11, partNumber: 'P-1', name: '物料一', type: 'RAW', lifecycleState: 'DRAFT', version: 'A/1', updatedAt: null }]),
    fetchTrashedBoms: vi.fn(async () => []),
    fetchTrashedDocs: vi.fn(async () => []),
    fetchTrashedDrawings: vi.fn(async () => []),
    fetchTrashedKnowledge: vi.fn(async () => [{ id: 21, title: '条目一', status: 'PUBLISHED' }]),
    fetchTrashedChanges: vi.fn(async () => [{ id: 31, ecrNumber: 'ECR-1', title: '变更一', state: 'REJECTED' }]),
    fetchTrashedProjects: vi.fn(async () => []),
    restorePart: vi.fn(async () => ({ id: 11 })),
  }
})

function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: 'admin', roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RecycleBinPage', () => {
  it('七个域 Tab 计数正确，物料 Tab 行可恢复', async () => {
    render(
      <PermContext.Provider value={permOf()}>
        <RecycleBinPage />
      </PermContext.Provider>,
    )
    expect(await screen.findByText('物料（1）')).toBeInTheDocument()
    expect(screen.getByText('BOM（0）')).toBeInTheDocument()
    expect(screen.getByText('文档（0）')).toBeInTheDocument()
    expect(screen.getByText('图纸（0）')).toBeInTheDocument()
    expect(screen.getByText('知识（1）')).toBeInTheDocument()
    expect(screen.getByText('变更（1）')).toBeInTheDocument()
    expect(screen.getByText('项目（0）')).toBeInTheDocument()
    expect(await screen.findByText('P-1')).toBeInTheDocument()
  })

  it('恢复动作调用 restorePart 并刷新', async () => {
    const { restorePart } = await import('../api/recycle')
    render(
      <PermContext.Provider value={permOf()}>
        <RecycleBinPage />
      </PermContext.Provider>,
    )
    const restoreBtns = await screen.findAllByText('恢复')
    fireEvent.click(restoreBtns[0])  // 恢复无 Popconfirm，直接执行
    await waitFor(() => {
      expect(restorePart).toHaveBeenCalledWith(11)
    }, { timeout: 3000 })
  })
})
