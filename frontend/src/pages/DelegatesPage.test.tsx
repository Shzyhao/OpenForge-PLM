import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DelegatesPage from './DelegatesPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// v1.27 P4#24 首批：审批委托页列表/撤销交互（mock API 层，无后端）
vi.mock('../api/user', () => ({
  fetchUserOptions: vi.fn(async () => [
    { id: 1, username: 'admin', displayName: '管理员' },
    { id: 2, username: 'bob', displayName: '鲍勃' },
  ]),
}))
vi.mock('../api/workflow', () => ({
  fetchDelegates: vi.fn(async () => [
    {
      id: 11, principalId: 1, agentId: 2, defKey: 'drawing-review', startTime: '2026-11-01',
      endTime: '2026-11-30', remark: '出差代批', enabled: 1,
    },
  ]),
  createDelegate: vi.fn(async () => ({ id: 12 })),
  deleteDelegate: vi.fn(async () => undefined),
  fetchDefs: vi.fn(async () => [{ id: 1, defKey: 'drawing-review', defName: '图纸评审' } as never]),
}))

function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: '管理员', roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DelegatesPage', () => {
  it('渲染委托规则列表（受托人/流程键）', async () => {
    render(<PermContext.Provider value={permOf()}><DelegatesPage /></PermContext.Provider>)
    expect(await screen.findByText('drawing-review')).toBeInTheDocument()
    expect(screen.getByText('出差代批')).toBeInTheDocument()
  })

  it('撤销委托：确认后调 deleteDelegate 并刷新', async () => {
    const { deleteDelegate, fetchDelegates } = await import('../api/workflow')
    render(<PermContext.Provider value={permOf()}><DelegatesPage /></PermContext.Provider>)
    expect(await screen.findByText('drawing-review')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /撤 销|撤销/ }))
    await waitFor(() => {
      expect(deleteDelegate).toHaveBeenCalledWith(11)
      expect(vi.mocked(fetchDelegates).mock.calls.length).toBeGreaterThanOrEqual(2)   // 初载 + 撤销后刷新
    })
  }, 15000)   // 满载（27 文件全量）下 5s 默认超时偶发不够——对齐 CI 慢机 +15s 先例（v1.30 A1 批注）

  it('空规则列表展示空态', async () => {
    const { fetchDelegates } = await import('../api/workflow')
    vi.mocked(fetchDelegates).mockResolvedValueOnce([] as never)
    render(<PermContext.Provider value={permOf()}><DelegatesPage /></PermContext.Provider>)
    expect(await screen.findByText('暂无委托规则')).toBeInTheDocument()
  })
})
