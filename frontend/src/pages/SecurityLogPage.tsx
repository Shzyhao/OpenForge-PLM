import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Input, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import { fetchAuditLogs, fetchLoginLogs, type AuditLog, type LoginLog } from '../api/security'

/** 安全日志页（方案 F6/F7）：登录日志 + 操作审计 */
export default function SecurityLogPage() {
  const [view, setView] = useState<'login' | 'audit'>('login')
  const [loginData, setLoginData] = useState<LoginLog[]>([])
  const [auditData, setAuditData] = useState<AuditLog[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      if (view === 'login') {
        const r = await fetchLoginLogs({ page, pageSize: 15, username: filter })
        setLoginData(r.list); setTotal(r.total)
      } else {
        const r = await fetchAuditLogs({ page, pageSize: 15, action: filter })
        setAuditData(r.list); setTotal(r.total)
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally { setLoading(false) }
  }, [view, page, filter])

  useEffect(() => { load() }, [load])

  const loginColumns: ColumnsType<LoginLog> = [
    { title: '时间', dataIndex: 'createdAt', width: 180, render: (v: string) => (v ?? '').replace('T', ' ').slice(0, 19) },
    { title: '用户名', dataIndex: 'username', width: 130 },
    { title: '结果', dataIndex: 'success', width: 90,
      render: (s: number) => <Tag color={s === 1 ? 'green' : 'red'}>{s === 1 ? '成功' : '失败'}</Tag> },
    { title: '原因', dataIndex: 'reason', width: 130,
      render: (r: string) => r === 'OK' ? <Tag color="green">{r}</Tag>
        : r === 'LOCKED' ? <Tag color="orange">锁定</Tag>
        : r === 'DISABLED' ? <Tag color="default">已停用</Tag> : <Tag>凭证错误</Tag> },
    { title: 'IP', dataIndex: 'ip' },
  ]

  const auditColumns: ColumnsType<AuditLog> = [
    { title: '时间', dataIndex: 'createdAt', width: 180, render: (v: string) => (v ?? '').replace('T', ' ').slice(0, 19) },
    { title: '操作人', dataIndex: 'operatorId', width: 90 },
    { title: '动作', dataIndex: 'action', width: 190, render: (a: string) => <Tag color="blue">{a}</Tag> },
    { title: '对象', width: 110, render: (_, r) => `${r.targetType}#${r.targetId ?? '-'}` },
    { title: '详情', dataIndex: 'detail' },
  ]

  return (
    <Card
      title={<Typography.Text strong>安全日志</Typography.Text>}
      extra={
        <Space>
          <Input.Search
            placeholder={view === 'login' ? '按用户名筛选' : '按动作筛选（如 USER_）'}
            allowClear style={{ width: 220 }}
            onSearch={(v) => { setPage(1); setFilter(v) }} />
          <Button icon={<ReloadOutlined />} onClick={load} />
        </Space>
      }
    >
      <Space style={{ marginBottom: 16 }}>
        <Button type={view === 'login' ? 'primary' : 'default'} onClick={() => { setView('login'); setPage(1) }}>登录日志</Button>
        <Button type={view === 'audit' ? 'primary' : 'default'} onClick={() => { setView('audit'); setPage(1) }}>操作审计</Button>
      </Space>
      {view === 'login'
        ? <Table<LoginLog> rowKey="id" columns={loginColumns} dataSource={loginData} loading={loading} size="small"
            pagination={{ current: page, total, pageSize: 15, onChange: setPage, showTotal: (t) => `共 ${t} 条` }} />
        : <Table<AuditLog> rowKey="id" columns={auditColumns} dataSource={auditData} loading={loading} size="small"
            pagination={{ current: page, total, pageSize: 15, onChange: setPage, showTotal: (t) => `共 ${t} 条` }} />}
    </Card>
  )
}
