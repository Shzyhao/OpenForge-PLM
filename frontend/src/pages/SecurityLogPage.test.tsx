import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SecurityLogPage from './SecurityLogPage'

// R14 第四批（v1.28）：安全日志页登录日志/审计日志两 Tab
vi.mock('../api/security', () => ({
  fetchLoginLogs: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 15 })),
  fetchAuditLogs: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 15 })),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SecurityLogPage', () => {
  it('默认 Tab 渲染登录日志（用户名行）', async () => {
    const { fetchLoginLogs } = await import('../api/security')
    vi.mocked(fetchLoginLogs).mockResolvedValueOnce({
      list: [
        { id: 1, username: 'alice', success: 1, reason: null, ip: '10.0.0.1', createdAt: '2026-11-21T10:00:00' },
      ] as never,
      total: 1, page: 1, pageSize: 15,
    })
    render(<SecurityLogPage />)
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(fetchLoginLogs).toHaveBeenCalled()
  })

  it('切审计 Tab 渲染操作审计行', async () => {
    const { fetchAuditLogs } = await import('../api/security')
    vi.mocked(fetchAuditLogs).mockResolvedValue({
      list: [
        { id: 1, operatorId: 7, action: 'CONN_CRED_CREATE', targetType: 'CREDENTIAL', targetId: 'cred_x', detail: '新建凭据', createdAt: '2026-11-21T11:00:00' },
      ] as never,
      total: 1, page: 1, pageSize: 15,
    })
    render(<SecurityLogPage />)
    const auditButton = await screen.findByRole('button', { name: /操\s*作\s*审\s*计/ })
    fireEvent.click(auditButton)
    expect(await screen.findByText('CONN_CRED_CREATE')).toBeInTheDocument()
  })
})
