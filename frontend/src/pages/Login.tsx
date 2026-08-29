import { Button, Card, Form, Grid, Input, message, theme, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { post, saveToken } from '../api/client'
import Logo from '../components/Logo'

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

/** 登录页（品牌化分栏）：左侧锻炉橙品牌区 + 右侧表单；窄屏隐藏品牌区 */
export default function Login() {
  const navigate = useNavigate()
  const { token } = theme.useToken()
  const screens = Grid.useBreakpoint()
  const showBrand = screens.md !== false

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
    <div style={{ display: 'flex', minHeight: '100vh', background: token.colorBgLayout }}>
      {showBrand && (
        <div style={{
          flex: '1 1 55%',
          background: 'linear-gradient(160deg, #F25C05 0%, #D94E04 55%, #B84303 100%)',
          color: '#fff',
          display: 'flex', flexDirection: 'column', justifyContent: 'center',
          padding: '0 8%',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 28 }}>
            <Logo size={52} />
            <Typography.Title level={2} style={{ color: '#fff', margin: 0 }}>OpenForge PLM</Typography.Title>
          </div>
          <Typography.Title level={3} style={{ color: '#fff', fontWeight: 600, marginTop: 0 }}>
            Open source PLM, forged with AI.
          </Typography.Title>
          <Typography.Paragraph style={{ color: 'rgba(255,255,255,0.92)', fontSize: 15, maxWidth: 480 }}>
            开源 · AI 原生 · 产品全生命周期管理平台。物料 BOM、变更流程、知识库与低代码动态对象，一个底座全部覆盖。
          </Typography.Paragraph>
          <ul style={{ listStyle: 'none', padding: 0, margin: '24px 0 0', display: 'flex', flexDirection: 'column', gap: 12 }}>
            {[
              '低代码动态对象：建模即得 API、物理表与 AI 可查',
              '模块化注册：不装某模块则不路由、不迁移、不显示',
              'AI 中台：自然语言查数、文档解析、知识库沉淀',
            ].map(text => (
              <li key={text} style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'rgba(255,255,255,0.95)' }}>
                <span style={{
                  width: 6, height: 6, borderRadius: 3, background: '#fff', display: 'inline-block', flexShrink: 0,
                }} />
                {text}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div style={{
        flex: '1 1 45%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}>
        <Card style={{ width: 380, boxShadow: '0 8px 24px rgba(0,0,0,0.08)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20, justifyContent: showBrand ? 'flex-start' : 'center' }}>
            {!showBrand && <Logo size={30} />}
            <Typography.Text strong style={{ fontSize: 18 }}>登录 OpenForge PLM</Typography.Text>
          </div>
          <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false}>
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input placeholder="用户名" autoComplete="username" size="large" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password placeholder="密码" autoComplete="current-password" size="large" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block size="large">
              登录
            </Button>
          </Form>
        </Card>
      </div>
    </div>
  )
}
