import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MetaObjectsPage from './MetaObjectsPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第五批（v1.28）：动态对象建模页列表/空态
vi.mock('../api/metadata', () => ({
  FIELD_TYPES: [{ value: 'STRING', label: '字符串' }, { value: 'NUMBER', label: '数值' }],
  fetchMetaObjects: vi.fn(async () => ({ items: [], total: 0, page: 1, pageSize: 15 })),
  fetchMetaObject: vi.fn(),
  createMetaObject: vi.fn(),
  updateMetaObject: vi.fn(),
  publishMetaObject: vi.fn(async () => undefined),
  previewDdl: vi.fn(async () => 'CREATE TABLE ...'),
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

describe('MetaObjectsPage', () => {
  it('列表渲染对象 key/显示名/状态', async () => {
    const { fetchMetaObjects } = await import('../api/metadata')
    vi.mocked(fetchMetaObjects).mockResolvedValueOnce({
      items: [
        { id: 1, objectKey: 'equipment', displayName: '设备台账', tableName: 'obj_equipment', status: 'PUBLISHED', version: 2, fieldCount: 8, createdAt: '2026-10-01' },
      ] as never,
      total: 1, page: 1, pageSize: 15,
    })
    render(<PermContext.Provider value={permOf()}><MetaObjectsPage /></PermContext.Provider>)
    expect(await screen.findByText('equipment')).toBeInTheDocument()
    expect(screen.getByText('设备台账')).toBeInTheDocument()
  })

  it('空列表展示空态', async () => {
    render(<PermContext.Provider value={permOf()}><MetaObjectsPage /></PermContext.Provider>)
    expect(await screen.findAllByText('No data')).not.toHaveLength(0)
  })
})
