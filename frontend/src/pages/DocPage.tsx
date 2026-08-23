import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { checkIn, checkOut, createDoc, fetchDocs, type DocInfo } from '../api/doc'

const DOC_TYPE_LABELS: Record<string, string> = {
  GENERAL: '通用文档',
  SPEC: '规格书',
  REPORT: '报告',
  DRAWING: '图纸',
}

/** 文档管理页（M2）：列表 + 新建 + 检入检出 */
export default function DocPage() {
  const [data, setData] = useState<DocInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [titleFilter, setTitleFilter] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchDocs({ page, pageSize: 10, title: titleFilter })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, titleFilter])

  useEffect(() => { load() }, [load])

  const act = async (id: number, action: 'check-out' | 'check-in') => {
    try {
      const doc = await (action === 'check-out' ? checkOut(id) : checkIn(id))
      message.success(`操作成功，当前版本 ${doc.versionMajor}/${doc.versionMinor}`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const columns: ColumnsType<DocInfo> = [
    { title: '文档编号', dataIndex: 'docNumber', width: 160 },
    { title: '标题', dataIndex: 'title' },
    { title: '类型', dataIndex: 'docType', width: 110, render: (t: string) => DOC_TYPE_LABELS[t] ?? t },
    {
      title: '版本', width: 80,
      render: (_, d) => `${d.versionMajor}/${d.versionMinor}`,
    },
    {
      title: '状态', dataIndex: 'checkedOutBy', width: 110,
      render: (by: number | null) => by !== null ? <Tag color="warning">已检出({by})</Tag> : <Tag color="green">可编辑</Tag>,
    },
    {
      title: '操作', width: 160,
      render: (_, doc) => (
        <Space size="small">
          {doc.checkedOutBy === null
            ? <Button size="small" onClick={() => act(doc.id, 'check-out')}>检出</Button>
            : <Button size="small" type="primary" onClick={() => act(doc.id, 'check-in')}>检入</Button>}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>文档管理</Typography.Text>}
      extra={
        <Space>
          <Input.Search
            placeholder="按标题搜索" allowClear style={{ width: 200 }}
            onSearch={(v) => { setPage(1); setTitleFilter(v) }}
          />
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建文档</Button>
        </Space>
      }
    >
      <Table<DocInfo>
        rowKey="id" columns={columns} dataSource={data} loading={loading}
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }}
      />
      <Modal
        title="新建文档" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            const doc = await createDoc(values.title, values.docType)
            message.success(`创建成功：${doc.docNumber}`)
            setCreateOpen(false)
            form.resetFields()
            load()
          } catch (e) {
            message.error(e instanceof Error ? e.message : '创建失败')
          }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical" initialValues={{ docType: 'GENERAL' }}>
          <Form.Item name="title" label="文档标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如：法兰盘设计规格书" />
          </Form.Item>
          <Form.Item name="docType" label="文档类型">
            <Select options={Object.entries(DOC_TYPE_LABELS).map(([v, l]) => ({ value: v, label: l }))} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
