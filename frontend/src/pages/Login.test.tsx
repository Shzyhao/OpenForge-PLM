import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import Login from './Login'

// v1.27 P4#24 首批：登录页表单校验/提交（mock API 层，无后端）
const postMock = vi.fn()
const saveTokenMock = vi.fn()

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return { ...original, post: (...args: unknown[]) => postMock(...args), saveToken: (...args: unknown[]) => saveTokenMock(...args) }
})

beforeEach(() => {
  vi.clearAllMocks()
})

function renderLogin() {
  return render(
    <MemoryRouter>
      <Login />
    </MemoryRouter>,
  )
}

describe('Login', () => {
  it('空提交触发必填校验（用户名/密码各自提示）', async () => {
    renderLogin()
    fireEvent.click(screen.getByRole('button', { name: '登 录' }))
    expect(await screen.findByText('请输入用户名')).toBeInTheDocument()
    expect(screen.getByText('请输入密码')).toBeInTheDocument()
    expect(postMock).not.toHaveBeenCalled()
  })

  it('填表提交：post 登录接口 + saveToken 落地', async () => {
    postMock.mockResolvedValue({
      accessToken: 'tok-1', tokenType: 'Bearer', expiresInSeconds: 3600, passwordStatus: 'NORMAL',
    })
    renderLogin()
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'pass-1' } })
    fireEvent.click(screen.getByRole('button', { name: '登 录' }))
    await waitFor(() => {
      expect(postMock).toHaveBeenCalledWith('/api/v1/auth/login', { username: 'admin', password: 'pass-1' })
      expect(saveTokenMock).toHaveBeenCalledWith('tok-1', 'NORMAL')
    })
  })

  it('登录失败展示错误消息（不落 token）', async () => {
    postMock.mockRejectedValue(new Error('用户名或密码错误'))
    renderLogin()
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'bad' } })
    fireEvent.click(screen.getByRole('button', { name: '登 录' }))
    expect(await screen.findByText('用户名或密码错误')).toBeInTheDocument()
    expect(saveTokenMock).not.toHaveBeenCalled()
  })
})
