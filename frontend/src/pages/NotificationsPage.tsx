import { useCallback, useEffect, useState } from 'react'
import { Badge, Button, Card, Empty, List, Popconfirm, Space, Switch, Table, Tabs, Tag, Typography, message } from 'antd'
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  EVENT_OPTIONS, fetchNotifications, fetchSubscriptions, markAllNotificationsRead,
  markNotificationRead, subscribeEvent, unsubscribeEvent,
  type NotifyMessage, type NotifySubscription,
} from '../api/notification'

/** 通知中心页（v1.23/v1.24）：收件箱分页、未读筛选、单条/全部已读 + 发布事件订阅设置。 */
export default function NotificationsPage() {
  const [data, setData] = useState<NotifyMessage[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [loading, setLoading] = useState(false)
  const [subs, setSubs] = useState<NotifySubscription[]>([])
  const [subSaving, setSubSaving] = useState<string | null>(null)
  const [tab, setTab] = useState<'inbox' | 'settings'>('inbox')

  const load = useCallback(async (p = page, u = unreadOnly) => {
    setLoading(true)
    try {
      const res = await fetchNotifications({ page: p, size: 20, unreadOnly: u })
      setData(res.list)
      setTotal(res.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, unreadOnly])

  useEffect(() => { load() }, [load])

  const loadSubs = useCallback(async () => {
    try {
      setSubs(await fetchSubscriptions())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '订阅加载失败')
    }
  }, [])

  const toggleSub = async (eventKey: string, checked: boolean) => {
    setSubSaving(eventKey)
    try {
      if (checked) {
        await subscribeEvent(eventKey)
        message.success('已订阅')
      } else {
        await unsubscribeEvent(eventKey)
        message.info('已取消订阅')
      }
      await loadSubs()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    } finally {
      setSubSaving(null)
    }
  }

  const subscribedKeys = new Set(subs.map((s) => s.eventKey))

  const readOne = async (n: NotifyMessage) => {
    if (n.readFlag) return
    try {
      await markNotificationRead(n.id)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const readAll = async () => {
    try {
      await markAllNotificationsRead()
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const columns: ColumnsType<NotifyMessage> = [
    {
      dataIndex: 'readFlag', width: 28,
      render: (v: number) => (v ? <Badge dot={false} /> : <Badge status="processing" />),
    },
    {
      dataIndex: 'title',
      render: (v: string, n) => (
        <Space>
          <Typography.Text strong={!n.readFlag}>{v}</Typography.Text>
          {n.bizType && <Tag>{n.bizType}</Tag>}
        </Space>
      ),
    },
    { dataIndex: 'content', ellipsis: true },
    { dataIndex: 'createdAt', width: 180 },
    {
      width: 90,
      render: (_, n) => (!n.readFlag
        ? <Button size="small" icon={<CheckOutlined />} onClick={() => readOne(n)}>已读</Button>
        : <Typography.Text type="secondary">已读</Typography.Text>),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>通知中心</Typography.Text>}
      extra={
        <Space>
          <Popconfirm title="将所有未读通知标记为已读？" onConfirm={readAll}>
            <Button icon={<CheckOutlined />}>全部已读</Button>
          </Popconfirm>
          <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
        </Space>
      }
    >
      <Tabs
        activeKey={unreadOnly ? 'unread' : (tab === 'settings' ? 'settings' : 'all')}
        onChange={(k) => {
          if (k === 'settings') {
            setTab('settings')
            loadSubs()
          } else {
            setTab('inbox')
            setUnreadOnly(k === 'unread')
            setPage(1)
          }
        }}
        items={[
          { key: 'all', label: '全部' },
          { key: 'unread', label: '未读' },
          { key: 'settings', label: '通知设置' },
        ]}
      />
      {tab === 'settings' ? (
        <List
          dataSource={EVENT_OPTIONS}
          rowKey={(o) => o.key}
          renderItem={(o) => (
            <List.Item
              actions={[
                <Switch key="sw"
                  checked={subscribedKeys.has(o.key)}
                  loading={subSaving === o.key}
                  onChange={(checked) => toggleSub(o.key, checked)} />,
              ]}
            >
              <List.Item.Meta title={o.label} description={o.desc} />
            </List.Item>
          )}
        />
      ) : (
        <Table<NotifyMessage>
          rowKey="id" loading={loading} dataSource={data} columns={columns}
          pagination={{ current: page, pageSize: 20, total, showSizeChanger: false,
            onChange: (p) => setPage(p) }}
          locale={{ emptyText: <Empty description="暂无通知" /> }}
          onRow={(n) => ({ onClick: () => readOne(n), style: { cursor: n.readFlag ? 'default' : 'pointer' } })}
        />
      )}
    </Card>
  )
}
