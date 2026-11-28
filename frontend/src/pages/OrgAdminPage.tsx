import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Checkbox, Form, Input, InputNumber, Modal, Select, Space, Table, Tree, Typography, message } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createOrg, deleteOrg, fetchOrgTree, fetchOrgUsers, updateOrg,
  type OrgNode, type OrgUser,
} from '../api/org'
import { assignUserOrg, fetchUsers, type AdminUser } from '../api/user'

/** 组织架构管理（十轮收口：OrgController 全量接线 + 用户挂接） */
export default function OrgAdminPage() {
  const [tree, setTree] = useState<OrgNode[]>([])
  const [selected, setSelected] = useState<OrgNode | null>(null)
  const [users, setUsers] = useState<OrgUser[]>([])
  const [includeChildren, setIncludeChildren] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingParent, setEditingParent] = useState<OrgNode | null>(null) // null=编辑选中，{}
  const [editing, setEditing] = useState<OrgNode | null>(null)
  const [assignOpen, setAssignOpen] = useState(false)
  const [candidates, setCandidates] = useState<AdminUser[]>([])
  const [assignUserId, setAssignUserId] = useState<number | null>(null)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    try {
      setTree(await fetchOrgTree())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }, [])

  const loadUsers = useCallback(async (org: OrgNode | null, withChildren: boolean) => {
    if (!org) { setUsers([]); return }
    try {
      setUsers(await fetchOrgUsers(org.id, withChildren))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '成员加载失败')
    }
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadUsers(selected, includeChildren) }, [selected, includeChildren, loadUsers])

  const toTreeData = useCallback((nodes: OrgNode[]): DataNode[] =>
    nodes.map((n) => ({
      key: n.id,
      title: `${n.orgName}（${n.orgCode}）`,
      children: n.children?.length ? toTreeData(n.children) : undefined,
    })), [])

  const findNode = useCallback((nodes: OrgNode[], id: number): OrgNode | null => {
    for (const n of nodes) {
      if (n.id === id) return n
      const hit = findNode(n.children ?? [], id)
      if (hit) return hit
    }
    return null
  }, [])

  const onSelect = (keys: React.Key[]) => {
    setSelected(keys.length ? findNode(tree, keys[0] as number) : null)
  }

  const openCreate = (parent: OrgNode | null) => {
    setEditing(null); setEditingParent(parent)
    form.setFieldsValue({ orgCode: '', orgName: '', sortOrder: 0 })
    setEditorOpen(true)
  }

  const openRename = (org: OrgNode) => {
    setEditing(org); setEditingParent(null)
    form.setFieldsValue({ orgCode: org.orgCode, orgName: org.orgName, sortOrder: org.sortOrder })
    setEditorOpen(true)
  }

  const submitEditor = async () => {
    const values = await form.validateFields()
    try {
      if (editing) {
        await updateOrg(editing.id, { orgName: values.orgName, sortOrder: values.sortOrder })
        message.success('已更新')
      } else {
        await createOrg({
          orgCode: values.orgCode, orgName: values.orgName, sortOrder: values.sortOrder,
          parentId: editingParent?.id ?? null,
        })
        message.success('已创建')
      }
      setEditorOpen(false)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const doDelete = async (org: OrgNode) => {
    Modal.confirm({
      title: `删除组织 ${org.orgName}？`,
      content: '有子组织或挂靠用户时将被拒绝。',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteOrg(org.id)
          message.success('已删除')
          setSelected(null)
          await load()
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除失败')
        }
      },
    })
  }

  const openAssign = async () => {
    setAssignUserId(null)
    try {
      const result = await fetchUsers({ page: 1, pageSize: 100 })
      setCandidates(result.list)
      setAssignOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '用户列表加载失败')
    }
  }

  const doAssign = async () => {
    if (!selected || !assignUserId) return
    try {
      await assignUserOrg(assignUserId, selected.id)
      message.success('已挂入')
      setAssignOpen(false)
      await loadUsers(selected, includeChildren)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '挂接失败')
    }
  }

  const doUnassign = async (u: OrgUser) => {
    try {
      await assignUserOrg(u.id, null)
      message.success('已移出组织')
      if (selected) await loadUsers(selected, includeChildren)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const treeData = useMemo(() => toTreeData(tree), [tree, toTreeData])

  return (
    <Card
      title={<Typography.Text strong>组织架构</Typography.Text>}
      extra={<Button icon={<ReloadOutlined />} onClick={load} />}
    >
      <Space size="large" align="start" style={{ width: '100%' }}>
        <Card type="inner" title="组织树" style={{ width: 360, minHeight: 420 }}
          extra={<Button size="small" icon={<PlusOutlined />} onClick={() => openCreate(null)}>根组织</Button>}>
          <Tree treeData={treeData} onSelect={onSelect} blockNode />
          {selected && (
            <Space style={{ marginTop: 12 }} wrap>
              <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openCreate(selected)}>子组织</Button>
              <Button size="small" onClick={() => openRename(selected)}>重命名</Button>
              <Button size="small" danger onClick={() => doDelete(selected)}>删除</Button>
            </Space>
          )}
        </Card>
        <Card type="inner" title={selected ? `${selected.orgName} · 成员` : '成员（先选择左侧组织）'}
          style={{ minWidth: 520, flex: 1 }}
          extra={
            <Space>
              <Checkbox checked={includeChildren}
                onChange={(e) => setIncludeChildren(e.target.checked)}>含下级</Checkbox>
              <Button size="small" type="primary" disabled={!selected} onClick={openAssign}>挂入用户</Button>
            </Space>
          }>
          <Table<OrgUser> rowKey="id" dataSource={users} size="small" pagination={false}
            columns={[
              { title: '用户名', dataIndex: 'username', width: 140 },
              { title: '姓名', dataIndex: 'displayName' },
              {
                title: '操作', width: 90,
                render: (_, u) => <Button size="small" onClick={() => doUnassign(u)}>移出组织</Button>,
              },
            ]} />
        </Card>
      </Space>

      <Modal title={editing ? '重命名组织' : `新建组织${editingParent ? `（上级：${editingParent.orgName}）` : '（根）'}`}
        open={editorOpen} destroyOnClose
        onOk={submitEditor} onCancel={() => setEditorOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="orgCode" label="组织编码" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]+$/, message: '字母/数字/中划线' }]}>
            <Input disabled={!!editing} placeholder="如 RD-CENTER" />
          </Form.Item>
          <Form.Item name="orgName" label="组织名称" rules={[{ required: true }]}>
            <Input placeholder="如 研发中心" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`挂入用户 → ${selected?.orgName ?? ''}`} open={assignOpen} destroyOnClose
        onOk={doAssign} onCancel={() => setAssignOpen(false)}>
        <Select showSearch optionFilterProp="label" placeholder="选择用户" style={{ width: '100%' }}
          value={assignUserId ?? undefined}
          onChange={(v) => setAssignUserId(v as number)}
          options={candidates.map((u) => ({
            value: u.id,
            label: `${u.username}（${u.displayName ?? '未命名'}）`,
          }))} />
      </Modal>
    </Card>
  )
}
