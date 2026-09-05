import { useCallback, useState } from 'react'
import {
  Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tree,
  Typography, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, SearchOutlined } from '@ant-design/icons'
import {
  BOM_STATE_LABELS, BOM_USAGE_TYPE_LABELS, BomHeader, BomLineView, BomSubstituteView,
  addBomLine, addBomSubstitute, fetchBom, fetchBomLines, fetchParts, removeBomLine,
  removeBomSubstitute, reviseBom, updateBomSubstitute,
} from '../api/material'
import { BomExpandNode } from '../api/material'
import { get } from '../api/client'
import { usePerm } from '../perm/PermContext'

const USAGE_TAG_COLORS: Record<string, string> = { NORMAL: 'default', ALTERNATE: 'orange', OPTIONAL: 'blue' }

/** 已发布物料远程搜索选择（D7：草稿件不可被 BOM 引用） */
function PartSelect(props: { value?: number; onChange?: (v: number) => void; placeholder?: string }) {
  const [options, setOptions] = useState<{ label: string; value: number }[]>([])
  const [loading, setLoading] = useState(false)
  const search = useCallback(async (kw?: string) => {
    setLoading(true)
    try {
      const page = await fetchParts({ page: 1, pageSize: 20, name: kw || undefined, lifecycleState: 'RELEASED' })
      setOptions(page.list.map(p => ({ label: `${p.partNumber} ${p.name}`, value: p.id })))
    } finally {
      setLoading(false)
    }
  }, [])
  return (
    <Select
      showSearch allowClear filterOption={false} loading={loading}
      value={props.value} onChange={props.onChange} placeholder={props.placeholder}
      onSearch={kw => search(kw)} onFocus={() => search()} options={options} style={{ minWidth: 240 }}
    />
  )
}

interface AntTreeNode {
  title: React.ReactNode
  key: string
  children?: AntTreeNode[]
}

function toTree(node: BomExpandNode, path: string): AntTreeNode {
  const key = `${path}/${node.partNumber}×${node.quantity}`
  const subChildren: AntTreeNode[] = (node.substitutes ?? []).map((s, i) => ({
    key: `${key}#sub${i}`,
    title: (
      <span>
        <Tag color="orange">替代#{s.priority}</Tag>
        {s.partNumber} — {s.name}
        <Typography.Text type="secondary"> ×系数{s.qtyCoefficient}</Typography.Text>
      </span>
    ),
  }))
  return {
    title: (
      <span>
        {node.partNumber} — {node.name}
        <Typography.Text type="secondary"> ×{node.quantity}</Typography.Text>
      </span>
    ),
    key,
    children: [...node.children.map(c => toTree(c, key)), ...subChildren],
  }
}

/** BOM 行管理（含替代组）与多层展开。草稿可编辑行/替代组；发布版可升版。 */
export default function BomPage() {
  const { hasPerm } = usePerm()
  const canManage = hasPerm('bom:manage')

  const [bomId, setBomId] = useState<number | null>(null)
  const [bom, setBom] = useState<BomHeader | null>(null)
  const [lines, setLines] = useState<BomLineView[]>([])
  const [loading, setLoading] = useState(false)
  const [tree, setTree] = useState<AntTreeNode[]>([])

  const [addLineOpen, setAddLineOpen] = useState(false)
  const [lineForm] = Form.useForm()
  const [subForm] = Form.useForm()
  const [subEditing, setSubEditing] = useState<{ lineId: number; sub: BomSubstituteView } | null>(null)

  const editable = bom?.lifecycleState === 'DRAFT'

  const reloadLines = useCallback(async (id: number) => {
    setLines(await fetchBomLines(id))
  }, [])

  const query = async () => {
    if (!bomId) return
    setLoading(true)
    try {
      const [header, lineRows, root] = await Promise.all([
        fetchBom(bomId),
        fetchBomLines(bomId),
        get<BomExpandNode>(`/api/v1/boms/expand?bomId=${bomId}&level=10`),
      ])
      setBom(header)
      setLines(lineRows)
      setTree([toTree(root, '')])
    } catch (e) {
      message.error(e instanceof Error ? e.message : '查询失败')
      setBom(null)
      setLines([])
      setTree([])
    } finally {
      setLoading(false)
    }
  }

  const openAddLine = () => {
    lineForm.resetFields()
    setAddLineOpen(true)
  }

  const submitAddLine = async () => {
    if (!bom) return
    const values = await lineForm.validateFields()
    try {
      await addBomLine(bom.id, {
        childPartId: values.childPartId,
        quantity: values.quantity,
        refDes: values.refDes || undefined,
        usageType: values.usageType || undefined,
      })
      message.success('行已添加')
      setAddLineOpen(false)
      await reloadLines(bom.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '添加失败')
    }
  }

  const submitRevise = async () => {
    if (!bom) return
    try {
      const next = await reviseBom(bom.id)
      message.success(`已升版 ${bom.version} → ${next.version}，新版为草稿`)
      setBomId(next.id)
      setBom(next)
      await reloadLines(next.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '升版失败')
    }
  }

  const submitAddSubstitute = async (lineId: number, values: {
    substitutePartId: number; priority?: number; qtyCoefficient?: number
  }) => {
    if (!bom) return
    try {
      await addBomSubstitute(bom.id, lineId, {
        substitutePartId: values.substitutePartId,
        priority: values.priority || undefined,
        qtyCoefficient: values.qtyCoefficient || undefined,
      })
      message.success('替代件已添加')
      subForm.resetFields()
      await reloadLines(bom.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '添加失败')
    }
  }

  const submitSubEdit = async () => {
    if (!bom || !subEditing) return
    const values = await subForm.validateFields()
    try {
      await updateBomSubstitute(bom.id, subEditing.lineId, subEditing.sub.id, {
        priority: values.priority ?? undefined,
        qtyCoefficient: values.qtyCoefficient ?? undefined,
      })
      message.success('替代件已更新')
      setSubEditing(null)
      await reloadLines(bom.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败')
    }
  }

  const substituteColumns = (line: BomLineView): TableColumnsType<BomSubstituteView> => [
    { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
    { title: '件号', dataIndex: 'partNumber', key: 'partNumber', width: 140 },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '替代系数', dataIndex: 'qtyCoefficient', key: 'qtyCoefficient', width: 100 },
    ...(canManage && editable
      ? [{
          title: '操作', key: 'op', width: 120,
          render: (_: unknown, sub: BomSubstituteView) => (
            <Space>
              <Button size="small" onClick={() => { setSubEditing({ lineId: line.id, sub }); subForm.setFieldsValue(sub) }}>编辑</Button>
              <Popconfirm title="确认移除该替代件？" onConfirm={() => removeSubstitute(line.id, sub.id)}>
                <Button size="small" danger>删除</Button>
              </Popconfirm>
            </Space>
          ),
        }]
      : []),
  ]

  const removeSubstitute = async (lineId: number, subId: number) => {
    if (!bom) return
    try {
      await removeBomSubstitute(bom.id, lineId, subId)
      message.success('替代件已移除')
      await reloadLines(bom.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '移除失败')
    }
  }

  const lineColumns: TableColumnsType<BomLineView> = [
    { title: '行号', dataIndex: 'position', key: 'position', width: 64 },
    { title: '子件件号', dataIndex: 'childPartNumber', key: 'childPartNumber', width: 140 },
    { title: '名称', dataIndex: 'childPartName', key: 'childPartName' },
    { title: '数量', dataIndex: 'quantity', key: 'quantity', width: 90 },
    { title: '位号', dataIndex: 'refDes', key: 'refDes', width: 140, render: v => v ?? '-' },
    {
      title: '用量类型', dataIndex: 'usageType', key: 'usageType', width: 90,
      render: v => <Tag color={USAGE_TAG_COLORS[v] ?? 'default'}>{BOM_USAGE_TYPE_LABELS[v] ?? v}</Tag>,
    },
    {
      title: '替代件', key: 'subs', width: 90,
      render: (_, line) => line.substitutes.length > 0
        ? <Tag color="orange">{line.substitutes.length} 个</Tag>
        : <Typography.Text type="secondary">-</Typography.Text>,
    },
    ...(canManage && editable
      ? [{
          title: '操作', key: 'op', width: 80,
          render: (_: unknown, line: BomLineView) => (
            <Popconfirm title="删除行将同时移除其替代组，确认？" onConfirm={() => removeLine(line.id)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          ),
        }]
      : []),
  ]

  const stateBadge = bom ? BOM_STATE_LABELS[bom.lifecycleState] : null

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Card
        title={<Typography.Text strong>BOM 行管理</Typography.Text>}
        extra={bom && (
          <Space>
            <Typography.Text type="secondary">{bom.bomNumber}</Typography.Text>
            <Typography.Text strong>{bom.version}</Typography.Text>
            {stateBadge && <Tag color={stateBadge.color}>{stateBadge.label}</Tag>}
            {canManage && editable && <Button type="primary" icon={<PlusOutlined />} onClick={openAddLine}>添加行</Button>}
            {canManage && bom.lifecycleState === 'RELEASED' && (
              <Popconfirm title={`升版将生成 ${bom.version.slice(0, -1)}${Number(bom.version.split('/')[1]) + 1} 新草稿（深拷贝行与替代组），确认？`}
                onConfirm={submitRevise}>
                <Button>升版</Button>
              </Popconfirm>
            )}
          </Space>
        )}
      >
        <Space style={{ marginBottom: 16 }}>
          <InputNumber placeholder="BOM ID" min={1} onChange={v => setBomId(v)} />
          <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={query}>查询</Button>
        </Space>
        {bom ? (
          <Table<BomLineView>
            rowKey="id" size="small" pagination={false} dataSource={lines}
            expandable={{
              expandedRowRender: line => (
                <div style={{ margin: '4px 0' }}>
                  <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
                    替代组（{line.substitutes.length}）
                  </Typography.Text>
                  <Table rowKey="id" size="small" pagination={false}
                    dataSource={line.substitutes} columns={substituteColumns(line)} />
                  {canManage && editable && (
                    <Form form={subForm} layout="inline" style={{ marginTop: 12 }}
                      onFinish={v => submitAddSubstitute(line.id, v)}>
                      <Form.Item name="substitutePartId" rules={[{ required: true, message: '选择替代件' }]}>
                        <PartSelect placeholder="选择替代件（已发布）" />
                      </Form.Item>
                      <Form.Item name="priority">
                        <InputNumber min={1} placeholder="优先级（空=组尾）" />
                      </Form.Item>
                      <Form.Item name="qtyCoefficient">
                        <InputNumber min={0.0001} step={0.1} placeholder="系数（默认1）" />
                      </Form.Item>
                      <Form.Item>
                        <Button type="primary" htmlType="submit" size="small" icon={<PlusOutlined />}>添加替代件</Button>
                      </Form.Item>
                    </Form>
                  )}
                </div>
              ),
              rowExpandable: () => true,
            }}
            columns={lineColumns}
          />
        ) : (
          <Typography.Text type="secondary">输入 BOM ID 并点击查询：查看行清单与替代组，草稿可编辑、发布版可升版</Typography.Text>
        )}
      </Card>

      <Card title={<Typography.Text strong>BOM 展开</Typography.Text>}>
        {tree.length > 0
          ? <Tree treeData={tree} defaultExpandAll showLine />
          : <Typography.Text type="secondary">查询 BOM 后此处展示多层结构（橙色节点为替代件标注，含环检测）</Typography.Text>}
      </Card>

      <Modal title="添加 BOM 行" open={addLineOpen} onOk={submitAddLine} onCancel={() => setAddLineOpen(false)}
        destroyOnClose okText="添加" cancelText="取消">
        <Form form={lineForm} layout="vertical">
          <Form.Item name="childPartId" label="子件（已发布物料）" rules={[{ required: true, message: '选择子件' }]}>
            <PartSelect placeholder="搜索已发布物料" />
          </Form.Item>
          <Form.Item name="quantity" label="数量" rules={[{ required: true, message: '输入数量' }]}>
            <InputNumber min={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="refDes" label="位号（可选，逗号分隔）">
            <Input placeholder="如 R1,R2,R3" />
          </Form.Item>
          <Form.Item name="usageType" label="用量类型" initialValue="NORMAL">
            <Select options={[
              { value: 'NORMAL', label: '正常' },
              { value: 'ALTERNATE', label: '替代' },
              { value: 'OPTIONAL', label: '选配' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`编辑替代件${subEditing ? `：${subEditing.sub.partNumber}` : ''}`}
        open={!!subEditing} onOk={submitSubEdit} onCancel={() => setSubEditing(null)}
        destroyOnClose okText="保存" cancelText="取消">
        <Form form={subForm} layout="vertical">
          <Form.Item name="priority" label="优先级（小者优先）">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="qtyCoefficient" label="替代系数">
            <InputNumber min={0.0001} step={0.1} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )

  async function removeLine(lineId: number) {
    if (!bom) return
    try {
      await removeBomLine(bom.id, lineId)
      message.success('行已删除')
      await reloadLines(bom.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}
