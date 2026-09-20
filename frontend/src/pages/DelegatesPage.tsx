import { useCallback, useEffect, useState } from 'react'
import { Button, Card, DatePicker, Empty, Form, Input, Modal, Select, Space, Table, Tabs, Tag, Typography, message } from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs, { type Dayjs } from 'dayjs'
import { fetchUserOptions, type UserOption } from '../api/user'
import { createDelegate, deleteDelegate, fetchDefs, fetchDelegates, type WorkflowDef, type WorkflowDelegate } from '../api/workflow'
import { usePerm } from '../perm/PermContext'

/** 审批委托（v1.23）：生效期内被委托人待办并集委托人任务；代办办理记录原指派人。 */
export default function DelegatesPage() {
  const { user } = usePerm()
  const [rules, setRules] = useState<WorkflowDelegate[]>([])
  const [users, setUsers] = useState<UserOption[]>([])
  const [defs, setDefs] = useState<WorkflowDef[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRules(await fetchDelegates())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const openModal = async () => {
    form.resetFields()
    setModalOpen(true)
    try {
      const [u, d] = await Promise.all([fetchUserOptions(), fetchDefs().catch(() => [])])
      setUsers(u.filter((x) => x.id !== user?.id))
      setDefs(d)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载用户失败')
    }
  }

  const save = async () => {
    const values = await form.validateFields()
    const range = values.range as [Dayjs, Dayjs]
    setSaving(true)
    try {
      await createDelegate({
        agentId: values.agentId,
        defKey: values.defKey,
        startTime: range[0].startOf('day').toISOString(),
        endTime: range[1] ? range[1].endOf('day').toISOString() : undefined,
        remark: values.remark,
      })
      message.success('委托规则已创建')
      setModalOpen(false)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (id: number) => {
    try {
      await deleteDelegate(id)
      message.success('已撤销')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '撤销失败')
    }
  }

  const userName = (id: number) => {
    if (id === user?.id) return `${user.displayName || user.username}（我）`
    const u = users.find((x) => x.id === id)
    return u ? `${u.displayName || u.username} (#${u.id})` : `用户 #${id}`
  }

  const columns: ColumnsType<WorkflowDelegate> = [
    { dataIndex: 'id', title: '#', width: 60 },
    {
      dataIndex: 'principalId', title: '委托人',
      render: (v: number) => (v === user?.id ? <Tag color="blue">{userName(v)}</Tag> : userName(v)),
    },
    { dataIndex: 'agentId', title: '被委托人', render: (v: number) => userName(v) },
    { dataIndex: 'defKey', title: '流程范围', render: (v: string | null) => v ?? <Tag>全部流程</Tag> },
    {
      key: 'range', title: '生效期',
      render: (_, r) => `${dayjs(r.startTime).format('YYYY-MM-DD HH:mm')} ~ ${r.endTime ? dayjs(r.endTime).format('YYYY-MM-DD HH:mm') : '长期'}`,
    },
    {
      dataIndex: 'enabled', title: '状态', width: 90,
      render: (v: number) => (v ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    { dataIndex: 'remark', title: '备注', ellipsis: true },
    {
      width: 90,
      render: (_, r) => (r.principalId === user?.id
        ? <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove(r.id)}>撤销</Button>
        : null),
    },
  ]

  const received = rules.filter((r) => r.agentId === user?.id)
  const sent = rules.filter((r) => r.principalId === user?.id)

  const tabTable = (rows: WorkflowDelegate[]) => (
    rows.length === 0 && !loading
      ? <Empty description="暂无委托规则" />
      : <Table<WorkflowDelegate> rowKey="id" loading={loading} dataSource={rows} columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: false }} />
  )

  return (
    <Card
      title={<Typography.Text strong>审批委托</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openModal}>新建委托</Button>
        </Space>
      }
    >
      <Tabs items={[
        { key: 'sent', label: `我发出的（${sent.length}）`, children: tabTable(sent) },
        { key: 'received', label: `我接收的（${received.length}）`, children: tabTable(received) },
      ]} />

      <Modal title="新建委托" open={modalOpen} onCancel={() => setModalOpen(false)} onOk={save}
        confirmLoading={saving} okText="创建" destroyOnClose>
        <Form form={form} layout="vertical">
          <Form.Item name="agentId" label="被委托人" rules={[{ required: true, message: '请选择被委托人' }]}>
            <Select placeholder="选择接管您待办的用户" showSearch optionFilterProp="label"
              options={users.map((u) => ({ value: u.id, label: `${u.displayName || u.username} (#${u.id})` }))} />
          </Form.Item>
          <Form.Item name="range" label="生效期（结束时间留空为长期）" rules={[{ required: true, message: '请选择生效期' }]}>
            <DatePicker.RangePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="defKey" label="流程范围">
            <Select allowClear placeholder="默认全部流程" showSearch optionFilterProp="label"
              options={defs.map((d) => ({ value: d.defKey, label: `${d.name} (${d.defKey})` }))} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} placeholder="如：出差期间由其代审" maxLength={200} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
