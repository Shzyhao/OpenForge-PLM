import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Input, Modal, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { deployDef, fetchDefs, type WorkflowDef } from '../api/workflow'

const EXAMPLE = `{
  "nodes": [
    {"id": "start", "type": "START"},
    {"id": "a1", "type": "APPROVAL", "name": "初审", "assignee": {"type": "ROLE", "value": "ADMIN"}},
    {"id": "end", "type": "END"}
  ],
  "edges": [
    {"from": "start", "to": "a1"}, {"from": "a1", "to": "end"}
  ]
}`

/** 流程定义管理（M3）：JSON 定义部署 + 版本列表；bpmn-js 可视化设计器随 M4 交付 */
export default function WorkflowDefsPage() {
  const [defs, setDefs] = useState<WorkflowDef[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [defKey, setDefKey] = useState('')
  const [name, setName] = useState('')
  const [definition, setDefinition] = useState(EXAMPLE)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDefs(await fetchDefs())
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const deploy = async () => {
    try {
      JSON.parse(definition) // 客户端先校验 JSON 合法性
    } catch {
      message.error('定义不是合法 JSON')
      return
    }
    try {
      const def = await deployDef({ defKey, name, definition })
      message.success(`部署成功：${def.defKey} v${def.version}`)
      setOpen(false)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '部署失败')
    }
  }

  const columns: ColumnsType<WorkflowDef> = [
    { title: '流程键', dataIndex: 'defKey', width: 160 },
    { title: '名称', dataIndex: 'name' },
    { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag>v{v}</Tag> },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color="green">{s}</Tag> },
    {
      title: '定义', width: 90,
      render: (_, def) => (
        <a onClick={() => Modal.info({
          title: `${def.defKey} v${def.version} 定义`,
          width: 640,
          content: <Input.TextArea readOnly autoSize value={JSON.stringify(JSON.parse(def.definition), null, 2)} />,
        })}>查看</a>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>流程定义</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>部署新流程</Button>
        </Space>
      }
    >
      <Table<WorkflowDef> rowKey="id" columns={columns} dataSource={defs} loading={loading}
        pagination={false} size="middle" />
      <Modal
        title="部署流程定义" open={open} onOk={deploy} onCancel={() => setOpen(false)} width={680} destroyOnClose
        okText="部署" okButtonProps={{ disabled: !defKey || !name || !definition }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Space>
            <Input placeholder="流程键（如 part-release）" value={defKey} onChange={(e) => setDefKey(e.target.value)} style={{ width: 240 }} />
            <Input placeholder="流程名称" value={name} onChange={(e) => setName(e.target.value)} style={{ width: 240 }} />
          </Space>
          <Input.TextArea
            rows={14} value={definition} onChange={(e) => setDefinition(e.target.value)}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            节点类型：START / APPROVAL（assignee: USER|ROLE|USERS，mode: ALL会签|ANY或签，rejectTo 回退节点）/ CONDITION（SpEL: #变量） / END。部署即新版本，在途实例按启动时快照执行。
          </Typography.Text>
        </Space>
      </Modal>
    </Card>
  )
}
