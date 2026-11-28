import { render, screen } from '@testing-library/react'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NumberRuleAdminPage from './NumberRuleAdminPage'

// R14 第四批（v1.28）：编号规则页列表/空态
vi.mock('../api/number', () => ({
  fetchNumberRules: vi.fn(async () => []),
  fetchNumberCounters: vi.fn(async () => []),
  previewNumber: vi.fn(async () => 'PRJ-0001'),
  createNumberRule: vi.fn(),
}))

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

describe('NumberRuleAdminPage', () => {
  it('列表渲染规则 key/名称/计数器水位', async () => {
    const { fetchNumberRules, fetchNumberCounters } = await import('../api/number')
    vi.mocked(fetchNumberRules).mockResolvedValueOnce([
      { id: 1, ruleKey: 'project_number', ruleName: '项目编号', segments: '[{"type":"CONST","value":"PRJ-"}]', resetPolicy: 'NONE', status: 'ACTIVE', tenantId: 0 },
    ] as never)
    vi.mocked(fetchNumberCounters).mockResolvedValueOnce([
      { ruleKey: 'project_number', period: '2026', currentValue: 41 },
    ] as never)
    render(<PermContext.Provider value={permOf()}><NumberRuleAdminPage /></PermContext.Provider>)
    expect((await screen.findAllByText('project_number')).length).toBeGreaterThan(0)   // 规则表+计数器表同 key
    expect(screen.getByText('项目编号')).toBeInTheDocument()
    expect(fetchNumberCounters).toHaveBeenCalled()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><NumberRuleAdminPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
