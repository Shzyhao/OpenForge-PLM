import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  PART_STATE_LABELS, PART_TYPE_LABELS,
  createPart, fetchCategoryTree, fetchParts, partAction,
  type CategoryNode, type Part,
} from '../api/material'
import { ApiError } from '../api/client'

/** 物料管理页（M2）：列表 + 筛选 + 新建 + 状态流转 */
export default function MaterialPage() {
  const [data, setData] = useState<Part[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [nameFilter, setNameFilter] = useState('')
  const [stateFilter, setStateFilter] = useState<string | undefined>()
  const [categories, setCategories] = useState<CategoryNode[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchParts({ page, pageSize: 10, name: nameFilter, lifecycleState: stateFilter })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, nameFilter, stateFilter])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    fetchCategoryTree().then(setCategories).catch(() => undefined)
  }, [])

  // 分类树展平为 Select 选项（缩进表示层级）
  const flatten = (nodes: CategoryNode[], depth = 0): { value: number; label: string }[] =>
    nodes.flatMap(n => [{ value: n.id, label: `${'　'.repeat(depth)}${n.categoryName}` }, ...flatten(n.children, depth + 1)])

  const columns: ColumnsType<Part> = [
    { title: '物料编号', dataIndex: 'partNumber', width: 160 },
    { title: '名称', dataIndex: 'name' },
    { title: '类型', dataIndex: 'type', width: 100, render: (t: string) => PART_TYPE_LABELS[t] ?? t },
    { title: '版本', dataIndex: 'version', width: 80 },
    {
      title: '状态', dataIndex: 'lifecycleState', width: 100,
      render: (s: string) => {
        const meta = PART_STATE_LABELS[s]
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : s
      },
    },
    {
      title: '操作', width: 220,
      render: (_, part) => (
        <Space size="small">
          {part.lifecycleState === 'DRAFT' && (
            <Button size="small" type="primary" ghost onClick={() => act(part.id, 'submit')}>提交</Button>
          )}
          {part.lifecycleState === 'REVIEWING' && (
            <>
              <Button size="small" type="primary" onClick={() => act(part.id, 'approve')}>发布</Button>
              <Button size="small" onClick={() => act(part.id, 'reject')}>驳回</Button>
            </>
          )}
        </Space>
      ),
    },
  ]

  const act = async (id: number, action: 'submit' | 'approve' | 'reject') => {
    try {
      await partAction(id, action)
      message.success('操作成功')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const onCreate = async () => {
    const values = await form.validateFields()
    try {
      const part = await createPart(values)
      message.success(`创建成功：${part.partNumber}`)
      setCreateOpen(false)
      form.resetFields()
      load()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '创建失败')
    }
  }

  return (
    <Card
      title={<Typography.Text strong>物料管理</Typography.Text>}
      extra={
        <Space>
          <Input.Search
            placeholder="按名称搜索" allowClear style={{ width: 200 }}
            onSearch={(v) => { setPage(1); setNameFilter(v) }}
          />
          <Select
            placeholder="状态" allowClear style={{ width: 120 }}
            options={Object.entries(PART_STATE_LABELS).map(([v, m]) => ({ value: v, label: m.label }))}
            onChange={(v) => { setPage(1); setStateFilter(v) }}
          />
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建物料</Button>
        </Space>
      }
    >
      <Table<Part>
        rowKey="id" columns={columns} dataSource={data} loading={loading}
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }}
      />
      <Modal title="新建物料" open={createOpen} onOk={onCreate} onCancel={() => setCreateOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ type: 'MADE' }}>
          <Form.Item name="name" label="物料名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：法兰盘" />
          </Form.Item>
          <Form.Item name="type" label="物料类型" rules={[{ required: true }]}>
            <Select options={Object.entries(PART_TYPE_LABELS).map(([v, l]) => ({ value: v, label: l }))} />
          </Form.Item>
          <Form.Item name="categoryId" label="物料分类" rules={[{ required: true, message: '请选择分类' }]}>
            <Select placeholder="选择分类" options={flatten(categories)} />
          </Form.Item>
          <Form.Item name="unit" label="计量单位">
            <Input placeholder="如：件" style={{ width: 120 }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
