import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Home from './Home'
import { get } from '../api/client'

// R14 收口（v1.30 A1）：工作台仪表盘——mock HTTP/用户/echarts 三层，断言统计卡汇总与三图渲染调度。
// echarts 画布标题不进 DOM，改经 setOption 参数取证；jsdom 无 canvas，故整模块 stub。
const setOptionCalls: { title?: { text?: string } }[] = []
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn((option: { title?: { text?: string } }) => setOptionCalls.push(option)) })),
  getInstanceByDom: vi.fn(() => undefined),
}))
vi.mock('../api/client', () => ({ get: vi.fn() }))
vi.mock('../api/user', () => ({
  fetchCurrentUser: vi.fn(async () => ({
    id: 1, username: 'admin', displayName: '锻造管理员', roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL',
  })),
}))

beforeEach(() => {
  vi.clearAllMocks()
  setOptionCalls.length = 0
})

function mockStats() {
  vi.mocked(get).mockImplementation(async (path: string) => {
    if (path === '/api/v1/parts/stats') return { draft: 2, released: 4 }
    if (path === '/api/v1/changes/stats') return { open: 3, applied: 2 }
    return { running: 1, completed: 2 }
  })
}

describe('Home', () => {
  it('渲染问候与三域统计卡（各状态值求和）', async () => {
    mockStats()
    render(<Home />)
    // 问候语与用户名同处一个 Title 的多个 text node，用正则命中整段
    expect(await screen.findByText(/锻造管理员/)).toBeInTheDocument()
    expect(screen.getByText('物料总数')).toBeInTheDocument()
    expect(screen.getByText('变更申请（ECR）')).toBeInTheDocument()
    expect(screen.getByText('流程实例')).toBeInTheDocument()
    // Object.values 求和：2+4 / 3+2 / 1+2
    expect(screen.getByText('6')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('三张图表各 init 一次且 setOption 携带对应标题', async () => {
    mockStats()
    const { init } = await import('echarts')
    render(<Home />)
    await screen.findByText('物料总数')
    await vi.waitFor(() => expect(init).toHaveBeenCalledTimes(3))
    expect(setOptionCalls.map(o => o.title?.text)).toEqual([
      '物料状态分布', '变更申请（ECR）状态', '流程实例状态',
    ])
  })

  it('统计 API 全部失败时静默降级：统计卡归零、不初始化图表', async () => {
    vi.mocked(get).mockRejectedValue(new Error('network down'))
    render(<Home />)
    expect(await screen.findByText('物料总数')).toBeInTheDocument()
    await vi.waitFor(() => expect(screen.getAllByText('0')).toHaveLength(3))
    const { init } = await import('echarts')
    expect(init).not.toHaveBeenCalled()
  })
})
