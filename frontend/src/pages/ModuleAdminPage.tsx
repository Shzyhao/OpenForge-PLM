import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Popconfirm, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import { disableModule, enableModule, fetchModules, type ModuleInfo } from '../api/modules'
import { usePerm } from '../perm/PermContext'

const TYPE_LABELS: Record<string, { label: string; color: string }> = {
  KERNEL: { label: '内核', color: 'purple' },
  BUSINESS: { label: '业务', color: 'blue' },
  AI: { label: 'AI', color: 'cyan' },
  EXTENSION: { label: '动态对象', color: 'geekblue' },
}

/** 模块管理页（A4 设计 3.5）：注册表全量视图 + 启停（停用即摘除：路由/菜单同步消失） */
export default function ModuleAdminPage() {
  const { hasPerm } = usePerm()
  const canManage = hasPerm('module:manage')
  const [data, setData] = useState<ModuleInfo[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setData(await fetchModules())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const toggle = async (row: ModuleInfo, action: 'enable' | 'disable') => {
    try {
      if (action === 'enable') {
        await enableModule(row.moduleKey)
        message.success(`${row.displayName} 已启用`)
      } else {
        await disableModule(row.moduleKey)
        message.success(`${row.displayName} 已停用（路由与菜单将在 30s 内摘除）`)
      }
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const columns: ColumnsType<ModuleInfo> = [
    { title: '模块', dataIndex: 'moduleKey', width: 140, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '显示名', dataIndex: 'displayName', width: 130 },
    {
      title: '类型', dataIndex: 'moduleType', width: 100,
      render: (t: string) => <Tag color={TYPE_LABELS[t]?.color}>{TYPE_LABELS[t]?.label ?? t}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => s === 'ENABLED' ? <Tag color="green">启用</Tag>
        : s === 'BROKEN' ? <Tag color="red">损坏</Tag> : <Tag color="default">停用</Tag>,
    },
    { title: '版本', dataIndex: 'version', width: 80 },
    {
      title: '路由前缀', dataIndex: 'routes', width: 260,
      render: (r: string) => {
        try {
          return (JSON.parse(r) as string[]).map((p) => <Tag key={p}>{p}</Tag>)
        } catch {
          return '-'
        }
      },
    },
    {
      title: '依赖', dataIndex: 'dependencies', width: 130,
      render: (d: string) => {
        try {
          const deps = JSON.parse(d) as string[]
          return deps.length === 0 ? '-' : deps.join(', ')
        } catch {
          return '-'
        }
      },
    },
    { title: '服务地址', dataIndex: 'serviceUri', width: 170, render: (v: string | null) => v ?? '-' },
    { title: '心跳', dataIndex: 'heartbeatAt', width: 165,
      render: (v: string) => (v ?? '').replace('T', ' ').slice(0, 19) },
    {
      title: '操作', width: 110,
      render: (_, row) => (
        <Space>
          {row.moduleType !== 'KERNEL' && row.status !== 'ENABLED' && (
            <Button size="small" type="primary" disabled={!canManage} onClick={() => toggle(row, 'enable')}>启用</Button>
          )}
          {row.moduleType !== 'KERNEL' && row.status !== 'DISABLED' && (
            <Popconfirm title={`停用 ${row.displayName}？`} description="路由与菜单将摘除；存在启用中的依赖方时会被拒绝。"
              onConfirm={() => toggle(row, 'disable')} disabled={!canManage}>
              <Button size="small" danger disabled={!canManage}>停用</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>模块管理</Typography.Text>}
      extra={<Space>
        <Typography.Text type="secondary">停用即摘除：网关路由与前端菜单同步移除</Typography.Text>
        <Button icon={<ReloadOutlined />} onClick={load} />
      </Space>}
    >
      <Table rowKey="id" size="middle" loading={loading} dataSource={data} columns={columns}
        pagination={false} />
    </Card>
  )
}
