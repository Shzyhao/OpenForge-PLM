import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import NotificationsPage from '../pages/NotificationsPage'
import { PermContext, type PermContextValue } from '../perm/PermContext'
import { EVENT_OPTIONS } from '../api/notification'

// R14 蓝本第一层：页面在「空数据 + 有权限」下可渲染出关键结构（不发真实请求）
vi.mock('../api/notification', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/notification')>()
  return {
    ...actual,
    fetchNotifications: vi.fn(async () => ({ list: [], total: 0, page: 1, pageSize: 20 })),
    fetchSubscriptions: vi.fn(async () => [
      { id: 1, userId: 1, eventKey: 'part.released' },
    ]),
  }
})

describe('NotificationsPage', () => {
  it('空收件箱渲染空态与页签（收件箱 + 通知设置）', async () => {
    render(
      <PermContext.Provider value={permOf()}>
        <NotificationsPage />
      </PermContext.Provider>,
    )
    expect(await screen.findByText('通知中心')).toBeInTheDocument()
    expect(screen.getByText('全部')).toBeInTheDocument()
    expect(screen.getByText('未读')).toBeInTheDocument()
    expect(screen.getByText('通知设置')).toBeInTheDocument()
    // 空态文案
    expect(await screen.findByText('暂无通知')).toBeInTheDocument()
  })

  it('通知设置 Tab 展示全部可订阅事件并反映已订阅状态', async () => {
    render(
      <PermContext.Provider value={permOf()}>
        <NotificationsPage />
      </PermContext.Provider>,
    )
    const settingsTab = await screen.findByText('通知设置')
    settingsTab.click()
    // 五个可订阅事件全部出现（EVENT_OPTIONS 与后端 SUBSCRIBABLE_EVENTS 对齐）
    for (const opt of EVENT_OPTIONS) {
      expect(await screen.findByText(opt.label)).toBeInTheDocument()
    }
    // 已订阅 part.released → 其开关为勾选态（第一个 Switch 对应物料发布行）
    await vi.waitFor(() => {
      const switches = document.querySelectorAll('button[role="switch"]')
      expect(switches.length).toBe(EVENT_OPTIONS.length)
      expect(switches[0]).toHaveAttribute('aria-checked', 'true')
    })
  })
})

function permOf(): PermContextValue {
  return {
    user: { id: 1, username: 'admin', displayName: 'admin', roles: ['ADMINS'], menus: [], permissions: [], userType: 'NORMAL' },
    hasPerm: () => true,
    hasMenu: () => true,
  }
}
