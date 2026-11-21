import { render, screen } from '@testing-library/react'
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

describe('NumberRuleAdminPage', () => {
  it('列表渲染规则 key/名称/计数器水位', async () => {
    const { fetchNumberRules, fetchNumberCounters } = await import('../api/number')
    vi.mocked(fetchNumberRules).mockResolvedValueOnce([
      { id: 1, ruleKey: 'project_number', ruleName: '项目编号', segments: '[{"type":"CONST","value":"PRJ-"}]', resetPolicy: 'NONE', status: 'ACTIVE', tenantId: 0 },
    ] as never)
    vi.mocked(fetchNumberCounters).mockResolvedValueOnce([
      { ruleKey: 'project_number', period: '2026', currentValue: 41 },
    ] as never)
    render(<NumberRuleAdminPage />)
    expect((await screen.findAllByText('project_number')).length).toBeGreaterThan(0)   // 规则表+计数器表同 key
    expect(screen.getByText('项目编号')).toBeInTheDocument()
    expect(fetchNumberCounters).toHaveBeenCalled()
  })

  it('空列表展示空态', async () => {
    render(<NumberRuleAdminPage />)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
