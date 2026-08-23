import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  ECR_STATE_LABELS, URGENCY_LABELS,
  createEcr, fetchEcrDetail, fetchEcrs, type ChangeRequest, type EcrDetail,
} from '../api/change'
import { INSTANCE_STATE_LABELS } from '../api/workflow'

/** 变更管理页（M3-3）：ECR 创建 + 列表 + 流程状态联动 */
export default function ChangePage() {
  const [data, setData] = useState<ChangeRequest[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchEcrs({ page, pageSize: 10 })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => { load() }, [load])

  const showDetail = async (id: number) => {
    try {
      const detail: EcrDetail = await fetchEcrDetail(id)
      const flow = detail.flowState
        ? `流程：${INSTANCE_STATE_LABELS[detail.flowState]?.label ?? detail.flowState}${detail.flowCurrentNode ? `（当前节点 ${detail.flowCurrentNode}）` : ''}`
        : '流程状态不可用'
      Modal.info({ title: `${detail.ecrNumber} — ${detail.title}`, width: 520,
        content: (
          <div style={{ marginTop: 12 }}>
            <p>{detail.reason ?? '（无变更原因说明）'}</p>
            <p><Tag color={URGENCY_LABELS[detail.urgency]?.color}>{URGENCY_LABELS[detail.urgency]?.label ?? detail.urgency}</Tag>
              <Tag color={ECR_STATE_LABELS[detail.state]?.color}>{ECR_STATE_LABELS[detail.state]?.label ?? detail.state}</Tag></p>
            <p style={{ color: '#888', fontSize: 12 }}>{flow} · 实例 #{detail.workflowInstanceId}</p>
          </div>
        ),
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '详情加载失败')
    }
  }

  const columns: ColumnsType<ChangeRequest> = [
    { title: 'ECR 编号', dataIndex: 'ecrNumber', width: 170 },
    { title: '标题', dataIndex: 'title', render: (_, r) => <a onClick={() => showDetail(r.id)}>{r.title}</a> },
    { title: '紧急度', dataIndex: 'urgency', width: 90,
      render: (u: string) => <Tag color={URGENCY_LABELS[u]?.color}>{URGENCY_LABELS[u]?.label ?? u}</Tag> },
    { title: '状态', dataIndex: 'state', width: 100,
      render: (s: string) => <Tag color={ECR_STATE_LABELS[s]?.color}>{ECR_STATE_LABELS[s]?.label ?? s}</Tag> },
  ]

  return (
    <Card
      title={<Typography.Text strong>变更管理（ECR）</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>发起变更</Button>
        </Space>
      }
    >
      <Table<ChangeRequest>
        rowKey="id" columns={columns} dataSource={data} loading={loading}
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }}
      />
      <Modal
        title="发起变更申请（ECR）" open={createOpen} destroyOnClose
        onOk={async () => {
          const values = await form.validateFields()
          try {
            const ecr = await createEcr(values)
            message.success(`已提交并进入评审流程：${ecr.ecrNumber}（到「我的待办」办理审批）`)
            setCreateOpen(false)
            form.resetFields()
            load()
          } catch (e) {
            message.error(e instanceof Error ? e.message : '提交失败')
          }
        }}
        onCancel={() => setCreateOpen(false)}
      >
        <Form form={form} layout="vertical" initialValues={{ urgency: 'NORMAL' }}>
          <Form.Item name="title" label="变更标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如：法兰盘材质由45#改为40Cr" />
          </Form.Item>
          <Form.Item name="reason" label="变更原因">
            <Input.TextArea rows={3} placeholder="说明问题、原因与预期收益" />
          </Form.Item>
          <Form.Item name="urgency" label="紧急程度">
            <Select options={Object.entries(URGENCY_LABELS).map(([v, m]) => ({ value: v, label: m.label }))} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
