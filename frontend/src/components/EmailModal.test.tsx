import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import EmailModal from './EmailModal'

// 邮箱改造 2026-11：联系邮箱自助弹窗——客户端校验拦截、提交与成功回调
vi.mock('../api/user', () => ({
  updateMyEmail: vi.fn(async () => undefined),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('EmailModal', () => {
  it('非法格式被客户端校验拦截，不发起请求', async () => {
    const { updateMyEmail } = await import('../api/user')
    render(<EmailModal open currentEmail={null} />)
    fireEvent.change(screen.getByRole('textbox', { name: /邮箱/ }), { target: { value: 'not-an-email' } })
    fireEvent.click(screen.getByRole('button', { name: /^保\s*存$/ }))
    await waitFor(() => {
      expect(screen.getByText('邮箱格式不正确')).toBeInTheDocument()
    })
    expect(updateMyEmail).not.toHaveBeenCalled()
  })

  it('合法邮箱提交后调用接口并回调 onSuccess', async () => {
    const { updateMyEmail } = await import('../api/user')
    const onSuccess = vi.fn()
    render(<EmailModal open currentEmail="old@example.com" onSuccess={onSuccess} />)
    const input = screen.getByRole('textbox', { name: /邮箱/ })
    await waitFor(() => {
      expect(input).toHaveValue('old@example.com')
    })
    fireEvent.change(input, { target: { value: 'new@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^保\s*存$/ }))
    await waitFor(() => {
      expect(updateMyEmail).toHaveBeenCalledWith('new@example.com')
      expect(onSuccess).toHaveBeenCalled()
    })
  })
})
