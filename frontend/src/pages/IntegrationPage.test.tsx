import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IntegrationPage from './IntegrationPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// v1.27 P4#24 首批：集成页三 Tab 空态 + 单步调用入口（mock API 层，无后端）
vi.mock('../api/connector', () => ({
  CONN_TYPES: [] as never, EVENT_TOPICS: [] as never, TRIGGER_TYPES: [] as never,
  createAiProvider: vi.fn(), createConnector: vi.fn(), createCredential: vi.fn(),
  deleteAiProvider: vi.fn(), deleteConnector: vi.fn(), deleteCredential: vi.fn(),
  disableConnector: vi.fn(), discardDlq: vi.fn(), fetchAiProviders: vi.fn(async () => emptyPage()),
  fetchConnector: vi.fn(), fetchConnectors: vi.fn(async () => emptyPage()),
  fetchConnectorPresets: vi.fn(async () => [] as never),
  fetchCredentials: vi.fn(async () => emptyPage()), fetchDlq: vi.fn(async () => emptyPage()),
  fetchExecLogs: vi.fn(async () => emptyPage()), invokeConnector: vi.fn(async () => ({
    status: 'SUCCESS', httpStatus: 200, durationMs: 12, rowsReturned: 1, body: '{"ok":1}', error: null,
  })),
  publishConnector: vi.fn(), replayDlq: vi.fn(), testAiProvider: vi.fn(), testConnector: vi.fn(),
  updateAiProvider: vi.fn(), updateConnector: vi.fn(), updateCredential: vi.fn(),
}))
vi.mock('../api/user', () => ({ fetchRoles: vi.fn(async () => []) }))
vi.mock('../components/ConnectorConfigPanel', () => ({ default: () => <div data-testid="config-panel" /> }))
vi.mock('../components/FlowDesigner', () => ({ default: () => <div data-testid="flow-designer" /> }))

function emptyPage() {
  return { list: [], total: 0, page: 1, pageSize: 15 }
}

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

describe('IntegrationPage', () => {
  it('连接器 Tab 空态渲染（无数据提示 + 新建按钮）', async () => {
    render(<PermContext.Provider value={permOf()}><IntegrationPage /></PermContext.Provider>)
    expect((await screen.findAllByText('No data')).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /新 建|新建/ })).toBeInTheDocument()
  })

  it('死信队列 Tab 空态可见；点刷新触发拉取', async () => {
    render(<PermContext.Provider value={permOf()}><IntegrationPage /></PermContext.Provider>)
    fireEvent.click(await screen.findByText('死信队列'))
    expect((await screen.findAllByText('No data')).length).toBeGreaterThan(0)
    const { fetchDlq } = await import('../api/connector')
    // 页面设计：进入 Tab 不自动拉取（空态轻量），刷新按钮触发
    const reloadButtons = screen.getAllByRole('button').filter((b) => b.querySelector('.anticon-reload'))
    fireEvent.click(reloadButtons[reloadButtons.length - 1])
    await waitFor(() => {
      expect(fetchDlq).toHaveBeenCalled()
    })
  })

  it('凭据库 Tab 空态渲染', async () => {
    render(<PermContext.Provider value={permOf()}><IntegrationPage /></PermContext.Provider>)
    fireEvent.click(await screen.findByText('凭据库'))
    expect((await screen.findAllByText('No data')).length).toBeGreaterThan(0)
  })

  it('已发布连接器行内测试入口打开执行面板并可正式调用', async () => {
    const { fetchConnectors, invokeConnector } = await import('../api/connector')
    vi.mocked(fetchConnectors).mockResolvedValue({
      list: [{
        id: 7, connCode: 'conn_demo', connName: '演示连接器', connType: 'HTTP_REST' as never,
        status: 'PUBLISHED', currentVersion: 3, description: null, triggerType: 'NONE', updatedAt: null,
      }],
      total: 1, page: 1, pageSize: 15,
    })
    render(<PermContext.Provider value={permOf()}><IntegrationPage /></PermContext.Provider>)
    const testButtons = await screen.findAllByText('测试', {}, { timeout: 4000 })
    fireEvent.click(testButtons[0])
    expect(await screen.findByText(/执行：演示连接器/, {}, { timeout: 4000 })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /正式调用/ }))
    await waitFor(() => {
      expect(invokeConnector).toHaveBeenCalledWith('conn_demo', {})
    }, { timeout: 4000 })
  }, 15000)
})
