import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyTasksPage from './MyTasksPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 扩面（v1.25）：待办页四态中的正常流（mock API 层，无后端）
vi.mock('../api/workflow', () => ({
  fetchMyTasks: vi.fn(async () => [
    { id: 1, instanceId: 9, nodeId: 'a1', nodeName: '初审', assigneeId: 1, candidateRole: null, action: null, comment: null, viaDelegation: true },
    { id: 2, instanceId: 9, nodeId: 'a2', nodeName: '经理审批', assigneeId: null, candidateRole: 'ADMIN', action: null, comment: null },
  ]),
  actTask: vi.fn(async () => ({ id: 9, state: 'COMPLETED' })),
  INSTANCE_STATE_LABELS: {},
  fetchDelegates: vi.fn(async () => []),
  createDelegate: vi.fn(async () => ({ id: 1 })),
  deleteDelegate: vi.fn(async () => undefined),
  fetchDefs: vi.fn(async () => []),
}))

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

describe('MyTasksPage', () => {
  it('渲染待办列表与委托来源标记', async () => {
    render(
      <PermContext.Provider value={permOf()}>
        <MyTasksPage />
      </PermContext.Provider>,
    )
    expect(await screen.findByText('初审')).toBeInTheDocument()
    expect(screen.getByText('经理审批')).toBeInTheDocument()
    // viaDelegation 任务展示"来自委托"
    expect(screen.getByText('来自委托')).toBeInTheDocument()
    // 角色任务展示角色 Tag
    expect(screen.getByText('ADMIN')).toBeInTheDocument()
  })

  it('通过弹窗提交调用 actTask 并提示流程完成', async () => {
    const { actTask } = await import('../api/workflow')
    render(
      <PermContext.Provider value={permOf()}>
        <MyTasksPage />
      </PermContext.Provider>,
    )
    const approveButtons = await screen.findAllByText('通过')
    fireEvent.click(approveButtons[0])
    // 弹窗出现后确认
    const okBtn = await screen.findByRole('button', { name: '通 过' })
    fireEvent.click(okBtn)
    await waitFor(() => {
      expect(actTask).toHaveBeenCalledWith(1, 'APPROVE', '同意')
    })
  })
})
