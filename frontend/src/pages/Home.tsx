import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'
import { getToken, TOKEN_KEY } from '../api/client'

/** 工作台占位页：M1 后续迭代替换为真实布局（侧边导航 + 待办 + AI 助手抽屉） */
export default function Home() {
  const navigate = useNavigate()
  const token = getToken()

  if (!token) {
    return <Result status="403" title="未登录" subTitle="请先登录后再访问工作台"
      extra={<Button type="primary" onClick={() => navigate('/login')}>去登录</Button>} />
  }

  return (
    <Result
      status="success"
      title="🔨 OpenForge PLM"
      subTitle="Open source PLM, forged with AI. 工作台建设中 —— M1 骨架已就绪"
      extra={<Button onClick={() => {
        localStorage.removeItem(TOKEN_KEY)
        navigate('/login')
      }}>退出登录</Button>}
    />
  )
}
