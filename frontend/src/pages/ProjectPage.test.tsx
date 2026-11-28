import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectPage from './ProjectPage'

// R14 第三批（v1.28）：项目页列表/任务面板/空态
vi.mock('../api/project', () => ({
  fetchProjects: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 10 })),
  fetchProjectTasks: vi.fn(async () => []),
  createProject: vi.fn(),
  addProjectTask: vi.fn(),
  closeProject: vi.fn(async () => undefined),
  moveTask: vi.fn(async () => ({ id: 1, title: 't', status: 'DONE', projectId: 1, assigneeId: null, dueDate: null })),
}))

const PROJECT = {
  id: 1, projectNumber: 'PRJ-001', name: '平台迁移', description: null,
  ownerId: null, status: 'ACTIVE', plannedStart: null, plannedEnd: null,
}

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

describe('ProjectPage', () => {
  it('列表渲染项目编号/名称', async () => {
    const { fetchProjects } = await import('../api/project')
    vi.mocked(fetchProjects).mockResolvedValueOnce({ list: [PROJECT] as never, total: 1, page: 1, pageSize: 10 })
    render(<PermContext.Provider value={permOf()}><ProjectPage /></PermContext.Provider>)
    expect(await screen.findByText('平台迁移')).toBeInTheDocument()
    expect(screen.getByText('PRJ-001')).toBeInTheDocument()
  })

  it('点"打开"加载项目任务列表', async () => {
    const { fetchProjects, fetchProjectTasks } = await import('../api/project')
    vi.mocked(fetchProjects).mockResolvedValueOnce({ list: [PROJECT] as never, total: 1, page: 1, pageSize: 10 })
    vi.mocked(fetchProjectTasks).mockResolvedValueOnce([
      { id: 9, title: '迁移数据库', status: 'TODO', projectId: 1, assigneeId: null, dueDate: null },
    ] as never)
    render(<PermContext.Provider value={permOf()}><ProjectPage /></PermContext.Provider>)
    fireEvent.click(await screen.findByText('打开'))
    await waitFor(() => {
      expect(fetchProjectTasks).toHaveBeenCalledWith(1)
    })
    expect(await screen.findByText('迁移数据库')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><ProjectPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
