import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Checkbox, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { usePerm } from '../perm/PermContext'
import {
  createNumberRule, fetchNumberCounters, fetchNumberRules, previewNumber, toggleNumberRule,
  type NumberCounter, type NumberRule, type NumberSegment,
} from '../api/number'

function describeSegments(json: string): string {
  try {
    const segs = JSON.parse(json) as NumberSegment[]
    return segs.map((s) => {
      if (s.type === 'CONST') return `"${s.value ?? ''}"`
      if (s.type === 'DATE') return s.pattern ?? 'yyyyMMdd'
      if (s.type === 'CATEGORY') return '分类码'
      return '#'.repeat(s.length ?? 5)
    }).join(' + ')
  } catch {
    return json
  }
}

/** 编号规则管理（十轮收口：平台模板 + 计数器水位，此前只能 SQL 手查） */
export default function NumberRuleAdminPage() {
  const { hasPerm } = usePerm()
  const canManage = hasPerm('number:manage')
  const [rules, setRules] = useState<NumberRule[]>([])
  const [counters, setCounters] = useState<NumberCounter[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [r, c] = await Promise.all([fetchNumberRules(), fetchNumberCounters()])
      setRules(r)
      setCounters(c)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const doPreview = async (rule: NumberRule) => {
    try {
      const next = await previewNumber(rule.ruleKey)
      Modal.info({ title: `预览下一个编号（不消费）：${rule.ruleKey}`, content: <Typography.Text copyable code style={{ fontSize: 16 }}>{next}</Typography.Text> })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预览失败')
    }
  }

  const doToggle = async (rule: NumberRule) => {
    try {
      await toggleNumberRule(rule.id, rule.status !== 'ACTIVE')
      message.success(rule.status === 'ACTIVE' ? '规则已停用' : '规则已启用')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const submitCreate = async () => {
    const v = await form.validateFields()
    const segments: NumberSegment[] = []
    if (v.prefix?.trim()) segments.push({ type: 'CONST', value: v.prefix.trim() })
    if (v.categorySegment) segments.push({ type: 'CATEGORY' })
    if (v.datePattern) segments.push({ type: 'DATE', pattern: v.datePattern })
    segments.push({ type: 'SEQ', length: v.seqLength ?? 5 })
    try {
      await createNumberRule({ ruleKey: v.ruleKey.trim(), ruleName: v.ruleName, segments, resetPolicy: v.resetPolicy })
      message.success('规则已创建')
      setOpen(false)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    }
  }

  const ruleColumns: ColumnsType<NumberRule> = [
    { title: 'ruleKey', dataIndex: 'ruleKey', width: 110 },
    { title: '名称', dataIndex: 'ruleName', width: 130 },
    { title: '段定义', dataIndex: 'segments', render: (s: string) => <Typography.Text code>{describeSegments(s)}</Typography.Text> },
    {
      title: '重置策略', dataIndex: 'resetPolicy', width: 90,
      render: (p: string) => ({ NONE: '不重置', DAILY: '每日', MONTHLY: '每月', YEARLY: '每年' }[p] ?? p),
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (s: string) => s === 'ACTIVE' ? <Tag color="green">ACTIVE</Tag> : <Tag>{s}</Tag>,
    },
    {
      title: '操作', width: 170, render: (_, r) => (
        <Space size={4}>
          <Button size="small" disabled={!canManage} onClick={() => doPreview(r)}>取号预览</Button>
          <Button size="small" disabled={!canManage} onClick={() => doToggle(r)}>{r.status === 'ACTIVE' ? '停用' : '启用'}</Button>
        </Space>
      ),
    },
  ]

  const counterColumns: ColumnsType<NumberCounter> = [
    { title: 'ruleKey', dataIndex: 'ruleKey', width: 110 },
    { title: '周期', dataIndex: 'period', width: 120, render: (p: string) => p || '—' },
    { title: '当前水位', dataIndex: 'currentValue', width: 110 },
  ]

  return (
    <Card
      title={<Typography.Text strong>编号规则</Typography.Text>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} disabled={!canManage} onClick={() => {
            form.setFieldsValue({ ruleKey: '', ruleName: '', prefix: '', categorySegment: false, datePattern: 'yyyyMMdd', seqLength: 5, resetPolicy: 'DAILY' })
            setOpen(true)
          }}>新建规则</Button>
        </Space>
      }
    >
      <Table<NumberRule> rowKey="id" columns={ruleColumns} dataSource={rules} loading={loading} size="middle" pagination={false} />
      <Card type="inner" title="取号计数器水位" style={{ marginTop: 16 }}>
        <Table<NumberCounter> rowKey={(r) => `${r.ruleKey}-${r.period}`} columns={counterColumns}
          dataSource={counters} size="small" pagination={false} />
      </Card>

      <Modal title="新建编号规则（平台模板，全部租户共享）" open={open} destroyOnClose
        onOk={submitCreate} onCancel={() => setOpen(false)}>
        <Form form={form} layout="vertical">
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item name="ruleKey" label="规则键" rules={[{ required: true }, { pattern: /^[a-z_]+$/, message: '小写字母/下划线' }]}>
              <Input placeholder="如 invoice" />
            </Form.Item>
            <Form.Item name="ruleName" label="名称" rules={[{ required: true }]}>
              <Input placeholder="如 发票编号" />
            </Form.Item>
          </Space>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item name="prefix" label="常量前缀（可选）">
              <Input placeholder="如 INV" />
            </Form.Item>
            <Form.Item name="categorySegment" valuePropName="checked" style={{ marginBottom: 0 }}>
              <Checkbox>分类码段（按分类独立计数）</Checkbox>
            </Form.Item>
            <Form.Item name="datePattern" label="日期段">
              <Select style={{ width: 130 }} options={[
                { value: 'yyyyMMdd', label: '日（yyyyMMdd）' },
                { value: 'yyyyMM', label: '月（yyyyMM）' },
                { value: 'yyyy', label: '年（yyyy）' },
                { value: '', label: '无日期段' },
              ]} />
            </Form.Item>
          </Space>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item name="seqLength" label="流水号位数">
              <InputNumber min={1} max={9} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="resetPolicy" label="重置策略">
              <Select style={{ width: 120 }} options={[
                { value: 'NONE', label: '不重置' },
                { value: 'DAILY', label: '每日' },
                { value: 'MONTHLY', label: '每月' },
                { value: 'YEARLY', label: '每年' },
              ]} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </Card>
  )
}
