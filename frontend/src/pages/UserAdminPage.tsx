import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { KeyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createUser, deleteUser, disableUser, enableUser, fetchRoles, fetchUsers, resetUserPassword,
  type AdminUser, type Role,
} from '../api/user'
import { usePerm } from '../perm/PermContext'

function randomPassword(): string {
  return 'Of@' + Math.random().toString(36).slice(2, 10) + Math.floor(Math.random() * 90 + 10)
}

/** 用户管理页（方案 D 组） */
export default function UserAdminPage() {
  const { user: me } = usePerm()
  const [data, setData] = useState<AdminUser[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [username, setUsername] = useState('')
  const [roles, setRoles] = useState<Role[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchUsers({ page, pageSize: 10, username })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, username])

  useEffect(() => { load() }, [load])
  useEffect(() => { fetchRoles().then(setRoles).catch(() => undefined) }, [])

  const columns: ColumnsType<AdminUser> = [
    { title: '用户名', dataIndex: 'username', width: 130 },
    { title: '姓名', dataIndex: 'displayName', width: 110 },
    { title: '邮箱', dataIndex: 'email' },
    {
      title: '类型', dataIndex: 'userType', width: 90,
      render: (t: string) => t === 'SUPER' ? <Tag color="red">admin</Tag> : <Tag>普通</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作', width: 230,
      render: (_, u) => {
        const isSuper = u.userType === 'SUPER'
        return (
          <Space size="small">
            {isSuper && <Typography.Text type="secondary" style={{ fontSize: 12 }}>仅 admin 本人可操作</Typography.Text>}
            {!isSuper && (
              <>
                {u.status === 'ACTIVE'
                  ? <Button size="small" disabled={me?.id === u.id} onClick={() => act(() => disableUser(u.id))}>停用</Button>
                  : <Button size="small" onClick={() => act(() => enableUser(u.id))}>启用</Button>}
                <Button size="small" icon={<KeyOutlined />} onClick={() => resetPwd(u)}>重置密码</Button>
                <Button size="small" danger disabled={me?.id === u.id} onClick={() => act(() => deleteUser(u.id))}>删除</Button>
              </>
            )}
          </Space>
        )
      },
    },
  ]

  const act = async (fn: () => Promise<unknown>) => {
    try { await fn(); message.success('操作成功'); load() }
    catch (e) { message.error(e instanceof Error ? e.message : '操作失败') }
  }

  const resetPwd = async (u: AdminUser) => {
    const pwd = randomPassword()
    try {
      await resetUserPassword(u.id, pwd)
      Modal.info({
        title: `已重置 ${u.username} 的密码`,
        content: <div><p>新密码（一次性展示，请立即转交）：</p>
          <Typography.Text copyable code style={{ fontSize: 16 }}>{pwd}</Typography.Text>
          <p style={{ color: '#888', marginTop: 8, fontSize: 12 }}>该用户下次登录将被强制修改密码。</p></div>,
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重置失败')
    }
  }

  return (
    <Card
      title={<Typography.Text strong>用户管理</Typography.Text>}
      extra={
        <Space>
          <Input.Search placeholder="按用户名搜索" allowClear style={{ width: 180 }}
            onSearch={(v) => { setPage(1); setUsername(v) }} />
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建用户</Button>
        </Space>
      }
    >
      <Table<AdminUser> rowKey="id" columns={columns} dataSource={data} loading={loading} size="middle"
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }} />
      <Modal
        title="新建用户" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            const u = await createUser(values)
            message.success(`创建成功：${u.username}（首次登录将强制修改密码）`)
            setCreateOpen(false); form.resetFields(); load()
          } catch (e) { message.error(e instanceof Error ? e.message : '创建失败') }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, min: 3, max: 64 }]}>
            <Input placeholder="登录用户名" />
          </Form.Item>
          <Form.Item name="password" label="初始密码" rules={[{ required: true },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/, message: '≥8位且包含字母与数字' }]}>
            <Input.Password placeholder="≥8位且包含字母与数字"
              addonAfter={<a onClick={() => form.setFieldValue('password', randomPassword())}>随机</a>} />
          </Form.Item>
          <Form.Item name="displayName" label="姓名"><Input /></Form.Item>
          <Form.Item name="email" label="邮箱"><Input /></Form.Item>
          <Form.Item name="roleIds" label="角色">
            <Select mode="multiple" placeholder="选择角色（可稍后分配）"
              options={roles.map(r => ({ value: r.id, label: `${r.roleName}(${r.roleCode})` }))} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
