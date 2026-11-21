import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LayoutDesignerPage from './LayoutDesignerPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第五批（v1.28）：布局设计器页——对象选择与布局加载
vi.mock('../api/metadata', () => ({
  fetchMetaObjects: vi.fn(async () => ({ items: [], total: 0, page: 1, pageSize: 50 })),
  fetchMetaObject: vi.fn(async () => ({
    id: 1, objectKey: 'equipment', displayName: '设备台账', tableName: 'obj_equipment',
    status: 'PUBLISHED', version: 1, createdAt: '2026-10-01',
    fields: [{ id: 1, fieldKey: 'name', displayName: '名称', fieldType: 'STRING', required: true }],
  })),
  fetchLayout: vi.fn(async () => ({ objectId: 1, layoutType: 'LIST', customized: false, fields: [] })),
  saveLayout: vi.fn(async () => undefined),
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

function renderPage() {
  return render(<PermContext.Provider value={permOf()}><LayoutDesignerPage /></PermContext.Provider>)
}

describe('LayoutDesignerPage', () => {
  it('无对象时空态（不拉布局）', async () => {
    const { fetchLayout } = await import('../api/metadata')
    renderPage()
    await waitFor(() => {
      expect(fetchLayout).not.toHaveBeenCalled()
    })
  })

  it('有对象时自动选中并加载布局', async () => {
    const { fetchMetaObjects, fetchLayout } = await import('../api/metadata')
    vi.mocked(fetchMetaObjects).mockResolvedValue({
      items: [
        { id: 1, objectKey: 'equipment', displayName: '设备台账', tableName: 'obj_equipment', status: 'PUBLISHED', version: 1, fieldCount: 1, createdAt: '2026-10-01' },
      ] as never,
      total: 1, page: 1, pageSize: 50,
    })
    renderPage()
    await waitFor(() => {
      expect(fetchLayout).toHaveBeenCalled()
    })
    expect((await screen.findAllByText(/设备台账/)).length).toBeGreaterThan(0)
  })
})
