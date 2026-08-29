import { useCallback, useEffect, useState } from 'react'
import {
  ArrowDownOutlined, ArrowUpOutlined, SaveOutlined,
} from '@ant-design/icons'
import {
  Button, Card, Input, InputNumber, Select,
  Space, Switch, Table, Tabs, Tag, Typography, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  fetchLayout, fetchMetaObject, fetchMetaObjects, saveLayout,
  type LayoutField, type MetaObjectDetail, type MetaObjectSummary,
} from '../api/metadata'
import { usePerm } from '../perm/PermContext'

interface DesignRow extends LayoutField {
  displayName: string
}

/** 表单/列表设计器（F3-2）：字段顺序/可见性/标签/列宽（表单）/跨列（表单） */
export default function LayoutDesignerPage() {
  const { hasPerm } = usePerm()
  const canManage = hasPerm('meta:manage')
  const [objects, setObjects] = useState<MetaObjectSummary[]>([])
  const [selectedKey, setSelectedKey] = useState<string | undefined>()
  const [meta, setMeta] = useState<MetaObjectDetail | null>(null)
  const [layoutType, setLayoutType] = useState<'FORM' | 'LIST'>('LIST')
  const [rows, setRows] = useState<DesignRow[]>([])
  const [customized, setCustomized] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetchMetaObjects().then((r) => {
      setObjects(r.items)
      if (r.items.length > 0 && !selectedKey) setSelectedKey(r.items[0].objectKey)
    }).catch(() => message.error('加载对象列表失败'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const load = useCallback(async () => {
    if (!selectedKey) return
    const summary = objects.find((o) => o.objectKey === selectedKey)
    if (!summary) return
    const detail = await fetchMetaObject(summary.id)
    setMeta(detail)
    const layout = await fetchLayout(summary.id, layoutType)
    setCustomized(layout.customized)
    const nameByKey = new Map(detail.fields.map((f) => [f.fieldKey, f.displayName]))
    setRows(layout.fields.map((f) => ({ ...f, displayName: nameByKey.get(f.fieldKey) ?? f.fieldKey })))
    setDirty(false)
  }, [selectedKey, objects, layoutType])

  useEffect(() => {
    load().catch(() => message.error('加载布局失败'))
  }, [load])

  const move = (index: number, delta: number) => {
    setRows((prev) => {
      const next = [...prev]
      const target = index + delta
      if (target < 0 || target >= next.length) return prev
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
    setDirty(true)
  }

  const update = (fieldKey: string, patch: Partial<DesignRow>) => {
    setRows((prev) => prev.map((r) => (r.fieldKey === fieldKey ? { ...r, ...patch } : r)))
    setDirty(true)
  }

  const save = async () => {
    if (!meta) return
    setSaving(true)
    try {
      const saved = await saveLayout(meta.id, layoutType, rows.map(({ fieldKey, visible, label, width, colSpan }) => ({
        fieldKey, visible, label: label ?? undefined, width: width ?? undefined, colSpan: colSpan ?? undefined,
      })))
      setCustomized(saved.customized)
      setDirty(false)
      message.success('布局已保存，动态数据页即刻生效')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<DesignRow> = [
    {
      title: '字段', dataIndex: 'fieldKey', width: 200,
      render: (_, r) => (
        <Space>
          <Button size="small" type="text" icon={<ArrowUpOutlined />} disabled={!canManage}
            onClick={() => move(rows.indexOf(r), -1)} />
          <Button size="small" type="text" icon={<ArrowDownOutlined />} disabled={!canManage}
            onClick={() => move(rows.indexOf(r), 1)} />
          <Typography.Text code>{r.fieldKey}</Typography.Text>
          <Typography.Text type="secondary">{r.displayName}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '显示', dataIndex: 'visible', width: 80,
      render: (_, r) => <Switch checked={r.visible} size="small" disabled={!canManage}
        onChange={(v) => update(r.fieldKey, { visible: v })} />,
    },
    {
      title: '自定义标签', dataIndex: 'label', width: 180,
      render: (_, r) => <Input value={r.label ?? ''} placeholder={r.displayName} disabled={!canManage}
        onChange={(e) => update(r.fieldKey, { label: e.target.value })} />,
    },
    layoutType === 'LIST'
      ? {
          title: '列宽(px)', dataIndex: 'width', width: 130,
          render: (_, r: DesignRow) => <InputNumber value={r.width ?? undefined} min={80} max={600}
            placeholder="默认" style={{ width: '100%' }} disabled={!canManage}
            onChange={(v) => update(r.fieldKey, { width: v ?? null })} />,
        }
      : {
          title: '跨列(1-3)', dataIndex: 'colSpan', width: 130,
          render: (_, r: DesignRow) => <InputNumber value={r.colSpan ?? 1} min={1} max={3}
            style={{ width: '100%' }} disabled={!canManage}
            onChange={(v) => update(r.fieldKey, { colSpan: v ?? 1 })} />,
        },
  ]

  return (
    <Card
      title={<Typography.Text strong>界面设计器</Typography.Text>}
      extra={
        <Space>
          <Select style={{ width: 220 }} placeholder="选择对象" value={selectedKey}
            onChange={(k) => { setSelectedKey(k); setDirty(false) }}
            options={objects.map((o) => ({ value: o.objectKey, label: `${o.displayName}（${o.objectKey}）` }))} />
          <Button type="primary" icon={<SaveOutlined />} loading={saving}
            disabled={!canManage || !dirty} onClick={save}>保存布局</Button>
        </Space>
      }
    >
      {meta && (
        <Typography.Paragraph type="secondary">
          {meta.displayName} · <Typography.Text code>{meta.tableName}</Typography.Text>
          {'　'}
          {customized ? <Tag color="blue">已定制</Tag> : <Tag>默认布局</Tag>}
          {dirty && <Tag color="orange">未保存</Tag>}
        </Typography.Paragraph>
      )}
      <Tabs activeKey={layoutType} onChange={(k) => setLayoutType(k as 'FORM' | 'LIST')} items={[
        { key: 'LIST', label: '列表布局（列序/可见/列宽）' },
        { key: 'FORM', label: '表单布局（字段序/可见/标签/跨列）' },
      ]} />
      <Table rowKey="fieldKey" size="small" dataSource={rows} columns={columns} pagination={false} />
    </Card>
  )
}
