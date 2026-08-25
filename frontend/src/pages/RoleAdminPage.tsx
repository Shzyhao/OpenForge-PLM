import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Checkbox, Form, Input, List, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import {
  addRoleMembers, createRole, deleteRole, fetchAllPermissions, fetchRoleMembers,
  fetchRolePermissionIds, fetchRoles, removeRoleMember, saveRolePermissions, updateRole,
  type PermNode, type Role,
} from '../api/user'

/** 角色与权限管理页（方案 B/C 组：角色 CRUD + 成员 + 权限矩阵） */
export default function RoleAdminPage() {
  const [roles, setRoles] = useState<Role[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  // 成员/矩阵弹窗状态
  const [memberRole, setMemberRole] = useState<Role | null>(null)
  const [members, setMembers] = useState<{ id: number; username: string; displayName: string | null }[]>([])
  const [allUsers, setAllUsers] = useState<{ id: number; username: string }[]>([])
  const [permRole, setPermRole] = useState<Role | null>(null)
  const [perms, setPerms] = useState<PermNode[]>([])
  const [checked, setChecked] = useState<Set<number>>(new Set())
  const [saving, setSaving] = useState(false)
  const [pendingAdd, setPendingAdd] = useState<number[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    try { setRoles(await fetchRoles()) } finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const openMembers = async (role: Role) => {
    setMemberRole(role)
    setMembers(await fetchRoleMembers(role.id))
    // 可选用户来自用户列表（简化取前 100）
    import('../api/user').then(async ({ fetchUsers }) => {
      const page = await fetchUsers({ page: 1, pageSize: 100 })
      setAllUsers(page.list.map(u => ({ id: u.id, username: u.username })))
    })
  }

  const openPerms = async (role: Role) => {
    setPermRole(role)
    const all = await fetchAllPermissions()
    setPerms(all)
    const codes = await fetchRolePermissionIds(role.id)
    const codeSet = new Set(codes)
    setChecked(new Set(all.filter(p => codeSet.has(p.permCode)).map(p => p.id)))
  }

  const togglePerm = (id: number) => {
    setChecked(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  const saveMatrix = async () => {
    if (!permRole) return
    setSaving(true)
    try {
      await saveRolePermissions(permRole.id, Array.from(checked))
      message.success(`已保存 ${permRole.roleName} 的权限配置`)
      setPermRole(null)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally { setSaving(false) }
  }

  const columns: ColumnsType<Role> = [
    { title: '编码', dataIndex: 'roleCode', width: 130 },
    { title: '名称', dataIndex: 'roleName', width: 130 },
    { title: '描述', dataIndex: 'description' },
    { title: '内置', dataIndex: 'builtin', width: 70, render: (b: number) => b === 1 ? <Tag>内置</Tag> : <Tag color="blue">自定义</Tag> },
    {
      title: '操作', width: 280,
      render: (_, r) => (
        <Space size="small">
          <a onClick={() => openMembers(r)}>成员</a>
          <a onClick={() => openPerms(r)}><SafetyOutlined /> 权限配置</a>
          <a onClick={() => {
            form.setFieldsValue({ id: r.id, roleName: r.roleName, description: r.description })
            setCreateOpen(true)
          }}>编辑</a>
          {r.builtin !== 1 && (
            <a style={{ color: '#ff4d4f' }} onClick={async () => {
              try { await deleteRole(r.id); message.success('已删除'); load() }
              catch (e) { message.error(e instanceof Error ? e.message : '删除失败') }
            }}>删除</a>
          )}
        </Space>
      ),
    },
  ]

  const menus = perms.filter(p => p.permType === 'MENU')
  const operations = perms.filter(p => p.permType === 'OPERATION')

  return (
    <Card
      title={<Typography.Text strong>角色与权限</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setCreateOpen(true) }}>新建角色</Button>
        </Space>
      }
    >
      <Table<Role> rowKey="id" columns={columns} dataSource={roles} loading={loading} size="middle" pagination={false} />

      {/* 新建/编辑角色 */}
      <Modal
        title={form.getFieldValue('id') ? '编辑角色' : '新建角色'} open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            if (values.id) {
              await updateRole(values.id, values.roleName, values.description)
              message.success('已更新')
            } else {
              await createRole(values.roleCode, values.roleName)
              message.success('已创建，可继续配置权限')
            }
            setCreateOpen(false); load()
          } catch (e) { message.error(e instanceof Error ? e.message : '保存失败') }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          {!form.getFieldValue('id') && (
            <Form.Item name="roleCode" label="角色编码" rules={[{ required: true }]}>
              <Input placeholder="如 QA_LEAD（唯一，创建后不可改）" />
            </Form.Item>
          )}
          <Form.Item name="roleName" label="角色名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      {/* 成员管理 */}
      <Modal
        title={`成员 — ${memberRole?.roleName ?? ''}`} open={!!memberRole} footer={null}
        onCancel={() => setMemberRole(null)} width={480}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
          用户列表：点击「＋」加入角色，点击「移除」解除（尚未加入的用户请先在列表上方的下拉框选择后点「批量」）。
        </Typography.Paragraph>
        <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
          <Select mode="multiple" placeholder="选择要添加的用户" style={{ width: '100%' }}
            value={pendingAdd} onChange={setPendingAdd}
            options={allUsers.filter(u => !members.some(m => m.id === u.id)).map(u => ({ value: u.id, label: u.username }))} />
          <Button type="primary" disabled={pendingAdd.length === 0} onClick={async () => {
            if (!memberRole) return
            await addRoleMembers(memberRole.id, pendingAdd)
            setPendingAdd([])
            setMembers(await fetchRoleMembers(memberRole.id))
          }}>添加</Button>
        </Space.Compact>
        <List
          size="small" dataSource={members} rowKey="id" locale={{ emptyText: '暂无成员' }}
          renderItem={(m) => (
            <List.Item actions={[
              <a key="add" onClick={async () => {
                if (!memberRole) return
                await addRoleMembers(memberRole.id, [m.id]); setMembers(await fetchRoleMembers(memberRole.id))
              }}>＋</a>,
              <a key="remove" style={{ color: '#ff4d4f' }} onClick={async () => {
                if (!memberRole) return
                await removeRoleMember(memberRole.id, m.id); setMembers(await fetchRoleMembers(memberRole.id))
              }}>移除</a>,
            ]}>
              {m.username}（{m.displayName ?? '-'}）
            </List.Item>
          )}
        />
      </Modal>

      {/* 权限矩阵 */}
      <Modal
        title={`权限配置 — ${permRole?.roleName ?? ''}`} open={!!permRole} width={620}
        onOk={saveMatrix} confirmLoading={saving} onCancel={() => setPermRole(null)} okText="保存配置"
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          菜单权限控制界面可见；操作权限控制界面内按钮（每个界面具备独立的增删改查权限）。
        </Typography.Paragraph>
        <Typography.Title level={5} style={{ fontSize: 13 }}>界面（菜单）权限</Typography.Title>
        <Space wrap size={[8, 4]}>
          {menus.map(m => (
            <Checkbox key={m.id} checked={checked.has(m.id)} onChange={() => togglePerm(m.id)}>
              {m.permName}
            </Checkbox>
          ))}
        </Space>
        <Typography.Title level={5} style={{ fontSize: 13, marginTop: 16 }}>操作权限</Typography.Title>
        <Space wrap size={[8, 4]}>
          {operations.map(o => (
            <Checkbox key={o.id} checked={checked.has(o.id)} onChange={() => togglePerm(o.id)}>
              {o.permName}
            </Checkbox>
          ))}
        </Space>
      </Modal>
    </Card>
  )
}
