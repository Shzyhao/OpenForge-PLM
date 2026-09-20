import { useCallback, useEffect, useState } from 'react'
import { Badge, Button, Empty, List, Popover, Typography } from 'antd'
import { BellOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import {
  fetchNotifications, fetchUnreadCount, markAllNotificationsRead, markNotificationRead,
  type NotifyMessage,
} from '../api/notification'

const POLL_MS = 30_000

/** 顶栏通知铃铛（v1.23）：未读角标 30s 轮询 + 最近通知面板。 */
export default function NotificationBell() {
  const [count, setCount] = useState(0)
  const [items, setItems] = useState<NotifyMessage[]>([])
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

  const refreshCount = useCallback(async () => {
    try {
      setCount(await fetchUnreadCount())
    } catch {
      /* 轮询失败静默（登录过期由 client 统一处理） */
    }
  }, [])

  useEffect(() => {
    refreshCount()
    const timer = setInterval(refreshCount, POLL_MS)
    return () => clearInterval(timer)
  }, [refreshCount])

  const loadRecent = useCallback(async () => {
    try {
      setItems((await fetchNotifications({ size: 8 })).list)
    } catch {
      /* 面板加载失败静默 */
    }
  }, [])

  const readOne = async (n: NotifyMessage) => {
    if (!n.readFlag) {
      try {
        await markNotificationRead(n.id)
        refreshCount()
      } catch { /* 忽略单条已读失败 */ }
    }
    navigate('/notifications')
    setOpen(false)
  }

  const readAll = async () => {
    try {
      await markAllNotificationsRead()
      refreshCount()
      loadRecent()
    } catch { /* 忽略 */ }
  }

  const content = (
    <div style={{ width: 340 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <Typography.Text strong>通知</Typography.Text>
        {count > 0 && <Button size="small" type="link" onClick={readAll}>全部已读</Button>}
      </div>
      {items.length === 0
        ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无通知" style={{ margin: '12px 0' }} />
        : (
          <List<NotifyMessage> size="small" dataSource={items} rowKey="id"
            renderItem={(n) => (
              <List.Item style={{ cursor: 'pointer', padding: '6px 0' }} onClick={() => readOne(n)}>
                <List.Item.Meta
                  title={<Typography.Text strong={!n.readFlag} style={{ fontSize: 13 }}>{n.title}</Typography.Text>}
                  description={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {(n.content ?? '')}
                  </Typography.Text>}
                />
                {!n.readFlag && <Badge status="processing" />}
              </List.Item>
            )}
          />
        )}
      <div style={{ textAlign: 'center', borderTop: '1px solid #f0f0f0', paddingTop: 6 }}>
        <Button type="link" size="small" onClick={() => { setOpen(false); navigate('/notifications') }}>
          查看全部
        </Button>
      </div>
    </div>
  )

  return (
    <Popover content={content} trigger="click" open={open}
      onOpenChange={(v) => { setOpen(v); if (v) loadRecent() }} placement="bottomRight">
      <Badge count={count} size="small" offset={[-2, 2]}>
        <Button type="text" aria-label="通知" icon={<BellOutlined style={{ fontSize: 18 }} />} />
      </Badge>
    </Popover>
  )
}
