import { Button, Card, Form, Input, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import { post, saveToken } from '../api/client'

interface LoginForm {
  username: string
  password: string
}

interface TokenData {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  passwordStatus?: string
  daysToExpiry?: number | null
}

export default function Login() {
  const navigate = useNavigate()

  const onFinish = async (values: LoginForm) => {
    try {
      const data = await post<TokenData>('/api/v1/auth/login', values)
      saveToken(data.accessToken, data.passwordStatus)
      message.success('登录成功')
      navigate('/')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败')
    }
  }

  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', background: '#f5f5f5',
    }}>
      <Card title="🔨 OpenForge PLM" style={{ width: 380 }} headStyle={{ textAlign: 'center' }}>
        <Form<LoginForm> layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
