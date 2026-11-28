import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import BomPage from './BomPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第二批（v1.28）：BOM 页初始态（无 bomId 时查询面板就绪）
vi.mock('../api/material', () => ({
  BOM_STATE_LABELS: { DRAFT: { label: '草稿', color: 'default' }, RELEASED: { label: '已发布', color: 'green' } },
  BOM_USAGE_TYPE_LABELS: { NORMAL: '常规', ALTERNATE: '替代', OPTIONAL: '选用' },
  VALIDITY_LABELS: {},
  fetchBom: vi.fn(async () => ({ id: 1, bomNumber: 'BOM-001', parentPartId: 1, bomType: 'EBOM', version: 'A/1', lifecycleState: 'DRAFT' })),
  fetchBomLines: vi.fn(async () => []),
  fetchParts: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 50 })),
  addBomLine: vi.fn(),
  addBomSubstitute: vi.fn(),
  removeBomLine: vi.fn(async () => undefined),
  removeBomSubstitute: vi.fn(async () => undefined),
  reviseBom: vi.fn(),
  updateBomSubstitute: vi.fn(),
}))
vi.mock('../api/client', () => ({
  get: vi.fn(async () => ({ id: 0, partNumber: 'P-ROOT', name: '根件', quantity: 1, usageType: 'NORMAL', validity: null, children: [], substitutes: [] })),
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
  return render(
    <MemoryRouter>
      <PermContext.Provider value={permOf()}><BomPage /></PermContext.Provider>
    </MemoryRouter>,
  )
}

describe('BomPage', () => {
  it('初始渲染：BOM ID 查询输入与查询按钮就绪（未查询前不发请求）', async () => {
    const { get } = await import('../api/client')
    renderPage()
    expect(screen.getByPlaceholderText('BOM ID')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /查\s*询/ })).toBeInTheDocument()
    expect(get).not.toHaveBeenCalled()
  })
})
