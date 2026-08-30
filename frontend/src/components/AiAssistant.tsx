import { useState } from 'react'
import { Button, Drawer, Input, Space, Tag, theme, Typography } from 'antd'
import { RobotOutlined, SendOutlined } from '@ant-design/icons'
import { aiChat } from '../api/client'

interface Msg {
  role: 'user' | 'assistant'
  content: string
  mode?: string
}

/** 全局 AI 助手抽屉（M4）：右上角常驻入口，离线模式展示降级标识 */
export default function AiAssistant() {
  const { token } = theme.useToken()
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [messages, setMessages] = useState<Msg[]>([
    { role: 'assistant', content: '你好，我是 OpenForge AI 助手。可以问我产品数据相关的问题（对话能力取决于模型配置状态）。' },
  ])

  const send = async () => {
    const question = input.trim()
    if (!question || loading) return
    setInput('')
    const next: Msg[] = [...messages, { role: 'user', content: question }]
    setMessages(next)
    setLoading(true)
    try {
      const result = await aiChat(next.filter(m => m.role === 'user' || m.content.length < 2000).map(m => ({ role: m.role, content: m.content })))
      setMessages([...next, { role: 'assistant', content: result.reply, mode: result.mode }])
    } catch (e) {
      setMessages([...next, { role: 'assistant', content: e instanceof Error ? e.message : '请求失败', mode: 'error' }])
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button
        type="primary" shape="circle" size="large" icon={<RobotOutlined />}
        onClick={() => setOpen(true)} title="AI 助手"
      />
      <Drawer
        title={<Space>🤖 AI 助手</Space>} placement="right" width={380}
        open={open} onClose={() => setOpen(false)}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
          {messages.map((m, i) => (
            <div key={i} style={{
              textAlign: m.role === 'user' ? 'right' : 'left',
            }}>
              <Typography.Text style={{
                display: 'inline-block', maxWidth: '95%', whiteSpace: 'pre-wrap',
                padding: '8px 12px', borderRadius: 8,
                background: m.role === 'user' ? '#F25C05' : token.colorFillSecondary,
                color: m.role === 'user' ? '#fff' : 'inherit',
                fontSize: 13,
              }}>{m.content}</Typography.Text>
              {m.mode === 'offline' && <div><Tag color="orange" style={{ marginTop: 4 }}>离线模式</Tag></div>}
            </div>
          ))}
          {loading && <Typography.Text type="secondary">思考中…</Typography.Text>}
        </div>
        <Space.Compact style={{ position: 'absolute', bottom: 16, left: 16, right: 16, width: 'calc(100% - 32px)' }}>
          <Input
            placeholder="输入问题…" value={input}
            onChange={(e) => setInput(e.target.value)}
            onPressEnter={send}
          />
          <Button type="primary" icon={<SendOutlined />} loading={loading} onClick={send} />
        </Space.Compact>
      </Drawer>
    </>
  )
}
