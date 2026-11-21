import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ObjectDataPage from './ObjectDataPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'

// R14 第五批（v1.28）：动态对象数据页——自动选中首个已发布对象并拉记录
vi.mock('../api/metadata', () => ({
  FIELD_TYPES: [{ value: 'STRING', label: '字符串' }, { value: 'NUMBER', label: '数值' }],
  fetchMetaObjects: vi.fn(async () => ({ items: [], total: 0, page: 1, pageSize: 50 })),
  fetchMetaObject: vi.fn(async () => ({
    id: 1, objectKey: 'equipment', displayName: '设备台账', tableName: 'obj_equipment',
    status: 'PUBLISHED', version: 1, createdAt: '2026-10-01',
    fields: [
      { id: 1, fieldKey: 'name', displayName: '名称', fieldType: 'STRING', required: true },
    ],
  })),
  fetchRecords: vi.fn(async () => ({ items: [{ id: 1, name: '空压机 A1' }], total: 1, page: 1, pageSize: 20 })),
  createRecord: vi.fn(),
  updateRecord: vi.fn(),
  deleteRecord: vi.fn(async () => undefined),
  fetchLayout: vi.fn(async () => ({ objectId: 1, layoutType: 'LIST', customized: false, fields: [{ fieldKey: 'name', visible: true }] })),
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
  return render(<PermContext.Provider value={permOf()}><ObjectDataPage /></PermContext.Provider>)
}

describe('ObjectDataPage', () => {
  it('无对象时空态', async () => {
    const { fetchMetaObjects } = await import('../api/metadata')
    renderPage()
    await waitFor(() => {
      expect(fetchMetaObjects).toHaveBeenCalled()
    })
  })

  it('自动选中首个已发布对象并加载记录', async () => {
    const { fetchMetaObjects, fetchRecords } = await import('../api/metadata')
    vi.mocked(fetchMetaObjects).mockResolvedValue({
      items: [
        { id: 1, objectKey: 'equipment', displayName: '设备台账', tableName: 'obj_equipment', status: 'PUBLISHED', version: 1, fieldCount: 1, createdAt: '2026-10-01' },
      ] as never,
      total: 1, page: 1, pageSize: 50,
    })
    renderPage()
    await waitFor(() => {
      expect(fetchRecords).toHaveBeenCalledWith('equipment', expect.anything())
    })
    expect(await screen.findByText('空压机 A1')).toBeInTheDocument()
  })
})
