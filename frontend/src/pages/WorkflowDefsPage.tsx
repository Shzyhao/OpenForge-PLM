import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Input, Modal, Space, Table, Tabs, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { EditOutlined, EyeOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { deployDef, fetchDefs, type WorkflowDef } from '../api/workflow'
import FlowDesigner, { buildDeployDefinition } from '../components/FlowDesigner'
import { autoLayout, parseDefinition, type FlowDef } from '../flow/flowModel'

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

const EMPTY_FLOW: FlowDef = { nodes: [], edges: [] }

/** 流程定义管理（M3 + 可视化设计器）：可视化/JSON 双模式部署 + 版本列表 + 只读预览 */
export default function WorkflowDefsPage() {
  const [defs, setDefs] = useState<WorkflowDef[]>([])
  const [loading, setLoading] = useState(false)
  // 部署弹窗（create = 新流程；edit = 基于既有版本部署新版本）
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'create' | 'edit'>('create')
  const [defKey, setDefKey] = useState('')
  const [name, setName] = useState('')
  const [flow, setFlow] = useState<FlowDef>(EMPTY_FLOW)
  const [jsonText, setJsonText] = useState('')
  const [activeTab, setActiveTab] = useState<'design' | 'json'>('design')
  const [deploying, setDeploying] = useState(false)
  // 只读预览弹窗
  const [preview, setPreview] = useState<{ title: string; flow: FlowDef } | null>(null)

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

  const openCreate = () => {
    setMode('create')
    setDefKey('')
    setName('')
    setFlow(parseDefinition(EXAMPLE).def ?? EMPTY_FLOW)
    setJsonText('')
    setActiveTab('design')
    setOpen(true)
  }

  const openEdit = (def: WorkflowDef) => {
    const parsed = parseDefinition(def.definition)
    if (!parsed.def) {
      message.error(`定义解析失败：${parsed.error}`)
      return
    }
    setMode('edit')
    setDefKey(def.defKey)
    setName(def.name)
    // 旧定义无坐标 → 自动布局后再进画布
    setFlow(parsed.def.nodes.some((n) => n.x === undefined) ? autoLayout(parsed.def) : parsed.def)
    setJsonText('')
    setActiveTab('design')
    setOpen(true)
  }

  const openPreview = (def: WorkflowDef) => {
    const parsed = parseDefinition(def.definition)
    if (!parsed.def) {
      message.error(`定义解析失败：${parsed.error}`)
      return
    }
    // 旧定义无坐标 → 自动布局后再进画布（与 openEdit 同规则；否则节点全叠 (0,0) 仅 END 可见）
    setPreview({
      title: `${def.defKey} v${def.version} · ${def.name}`,
      flow: parsed.def.nodes.some((n) => n.x === undefined) ? autoLayout(parsed.def) : parsed.def,
    })
  }

  // Tab 切换时双向同步：设计器 ↔ JSON 文本
  const onTabChange = (key: string) => {
    if (key === 'json') {
      setJsonText(JSON.stringify(flow, null, 2))
      setActiveTab('json')
    } else {
      const parsed = parseDefinition(jsonText)
      if (!parsed.def) {
        message.error(`JSON 解析失败：${parsed.error}`)
        return
      }
      setFlow(parsed.def)
      setActiveTab('design')
    }
  }

  const deploy = async () => {
    const { json, errors } = buildDeployDefinition(flow)
    if (errors.length) {
      message.error(`${errors[0]}${errors.length > 1 ? `（共 ${errors.length} 项）` : ''}`)
      return
    }
    setDeploying(true)
    try {
      const def = await deployDef({ defKey: defKey.trim(), name: name.trim(), definition: json! })
      message.success(`部署成功：${def.defKey} v${def.version}`)
      setOpen(false)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '部署失败')
    } finally {
      setDeploying(false)
    }
  }

  const columns: ColumnsType<WorkflowDef> = [
    { title: '流程键', dataIndex: 'defKey', width: 160 },
    { title: '名称', dataIndex: 'name' },
    { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag>v{v}</Tag> },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color="green">{s}</Tag> },
    {
      title: '操作', width: 190,
      render: (_, def) => (
        <Space>
          <a onClick={() => openPreview(def)}><EyeOutlined /> 查看</a>
          <a onClick={() => openEdit(def)}><EditOutlined /> 部署新版本</a>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>流程定义</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>部署新流程</Button>
        </Space>
      }
    >
      <Table<WorkflowDef> rowKey="id" columns={columns} dataSource={defs} loading={loading}
        pagination={false} size="middle" />
      <Modal
        title={mode === 'create' ? '部署流程定义' : `部署新版本：${defKey}`}
        open={open} onOk={deploy} onCancel={() => setOpen(false)} width={960}
        confirmLoading={deploying} okText="部署" cancelText="取消"
        okButtonProps={{ disabled: !defKey.trim() || !name.trim() }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Space>
            <Input
              placeholder="流程键（如 part-release）" value={defKey} style={{ width: 240 }}
              disabled={mode === 'edit'}
              onChange={(e) => setDefKey(e.target.value)} />
            <Input placeholder="流程名称" value={name} style={{ width: 240 }}
              onChange={(e) => setName(e.target.value)} />
          </Space>
          <Tabs activeKey={activeTab} onChange={onTabChange} items={[
            {
              key: 'design', label: '可视化设计',
              children: (
                <>
                  <FlowDesigner value={flow} onChange={setFlow} height={480} />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    拖拽移动节点；从节点右侧圆点拖出连线；点击节点/连线在右侧面板编辑；滚轮缩放、空白处拖动平移。部署即新版本，在途实例按启动时快照执行。
                  </Typography.Text>
                </>
              ),
            },
            {
              key: 'json', label: 'JSON 源码',
              children: (
                <>
                  <Input.TextArea
                    rows={18} value={jsonText} onChange={(e) => setJsonText(e.target.value)}
                    style={{ fontFamily: 'monospace', fontSize: 12 }}
                  />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    切回「可视化设计」时解析此 JSON 并载入画布。节点类型：START / APPROVAL（assignee: USER|ROLE|USERS）/ CONDITION（rules[].to 分支路由）/ END；x/y 为设计器布局坐标。
                  </Typography.Text>
                </>
              ),
            },
          ]} />
        </Space>
      </Modal>
      <Modal
        title={preview?.title} open={!!preview} onCancel={() => setPreview(null)} width={960}
        footer={<Button type="primary" onClick={() => setPreview(null)}>关闭</Button>}
      >
        {preview && <FlowDesigner value={preview.flow} onChange={() => {}} readOnly height={520} />}
      </Modal>
    </Card>
  )
}
