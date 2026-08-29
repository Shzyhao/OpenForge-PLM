import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, DatePicker, Form, Input, InputNumber, Modal, Popconfirm, Select,
  Space, Switch, Table, Tag, Typography, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import {
  FIELD_TYPES, createRecord, deleteRecord, fetchLayout, fetchMetaObject, fetchMetaObjects,
  fetchRecords, updateRecord, type DynamicRecord, type LayoutData, type MetaObjectDetail,
  type MetaObjectSummary,
} from '../api/metadata'
import { usePerm } from '../perm/PermContext'

interface FilterDraft {
  field: string
  op: 'eq' | 'like' | 'in'
  value: string
}

/** 动态对象数据页（F2 设计 3）：发布后即刻可用，表格/表单按元数据渲染 */
export default function ObjectDataPage() {
  const { hasPerm } = usePerm()
  const [objects, setObjects] = useState<MetaObjectSummary[]>([])
  const [selectedKey, setSelectedKey] = useState<string | undefined>()
  const [meta, setMeta] = useState<MetaObjectDetail | null>(null)
  const [rows, setRows] = useState<DynamicRecord[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize] = useState(15)
  const [sort, setSort] = useState<string | undefined>()
  const [filters, setFilters] = useState<string[]>([])
  const [draft, setDraft] = useState<FilterDraft | null>(null)
  const [loading, setLoading] = useState(false)
  const [listLayout, setListLayout] = useState<LayoutData | null>(null)
  const [formLayout, setFormLayout] = useState<LayoutData | null>(null)
  const [editing, setEditing] = useState<DynamicRecord | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  useEffect(() => {
    fetchMetaObjects().then((r) => {
      const published = r.items.filter((o) => o.status === 'PUBLISHED')
      setObjects(published)
      // EXTENSION 模块菜单深链：/meta/data?object=xxx 预选对象
      const fromQuery = new URLSearchParams(window.location.search).get('object')
      const preferred = fromQuery && published.some((o) => o.objectKey === fromQuery) ? fromQuery : undefined
      if (preferred) setSelectedKey(preferred)
      else if (published.length > 0 && !selectedKey) setSelectedKey(published[0].objectKey)
    }).catch(() => message.error('加载对象列表失败'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!selectedKey) {
      setMeta(null)
      return
    }
    const summary = objects.find((o) => o.objectKey === selectedKey)
    if (!summary) return
    fetchMetaObject(summary.id)
      .then(setMeta)
      .catch(() => message.error('加载对象定义失败'))
    // F3-2 设计器布局：未定制时后端返回默认派生（全可见按序），应用层无需分支
    fetchLayout(summary.id, 'LIST').then(setListLayout).catch(() => setListLayout(null))
    fetchLayout(summary.id, 'FORM').then(setFormLayout).catch(() => setFormLayout(null))
  }, [selectedKey, objects])

  const load = useCallback(async () => {
    if (!selectedKey) return
    setLoading(true)
    try {
      const r = await fetchRecords(selectedKey, { page, pageSize, filters, sort })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [selectedKey, page, pageSize, filters, sort])

  useEffect(() => {
    load()
  }, [load])

  const fieldDef = (key: string) => meta?.fields.find((f) => f.fieldKey === key)
  const canCreate = !!selectedKey && hasPerm(`${selectedKey}:create`)
  const canUpdate = !!selectedKey && hasPerm(`${selectedKey}:update`)
  const canDelete = !!selectedKey && hasPerm(`${selectedKey}:delete`)

  const columns: ColumnsType<DynamicRecord> = useMemo(() => {
    if (!meta) return []
    const layoutByKey = new Map((listLayout?.fields ?? []).map((l) => [l.fieldKey, l]))
    const orderedFields = listLayout
      ? listLayout.fields.filter((l) => l.visible).map((l) => meta.fields.find((f) => f.fieldKey === l.fieldKey)!).filter(Boolean)
      : meta.fields
    const fieldCols: ColumnsType<DynamicRecord> = orderedFields.map((f) => ({
      title: layoutByKey.get(f.fieldKey)?.label || f.displayName,
      dataIndex: f.fieldKey,
      key: f.fieldKey,
      sorter: true,
      width: layoutByKey.get(f.fieldKey)?.width ?? undefined,
      render: (v: unknown) => {
        if (v === null || v === undefined) return <Typography.Text type="secondary">-</Typography.Text>
        if (f.fieldType === 'BOOLEAN') return v === 1 || v === true ? <Tag color="green">是</Tag> : <Tag>否</Tag>
        if (f.fieldType === 'REFERENCE') return <Typography.Text code>#{String(v)}</Typography.Text>
        if (f.fieldType === 'DATE') return String(v).replace('T', ' ').slice(0, 19)
        if (f.fieldType === 'NUMBER') return String(v)
        return String(v)
      },
    }))
    return [
      { title: 'ID', dataIndex: 'id', width: 70, sorter: true },
      ...fieldCols,
      { title: '创建时间', dataIndex: 'createdAt', width: 170,
        render: (v: string) => (v ?? '').replace('T', ' ').slice(0, 19) },
      {
        title: '操作', width: 140,
        render: (_, row) => (
          <Space>
            <Button size="small" disabled={!canUpdate} onClick={() => openEdit(row)}>编辑</Button>
            <Popconfirm title="软删除该记录？" disabled={!canDelete} onConfirm={() => remove(row.id)}>
              <Button size="small" danger disabled={!canDelete}>删除</Button>
            </Popconfirm>
          </Space>
        ),
      },
    ]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [meta, listLayout, canUpdate, canDelete])

  const openEdit = (row: DynamicRecord) => {
    setEditing(row)
    const values: Record<string, unknown> = {}
    meta?.fields.forEach((f) => {
      const v = row[f.fieldKey]
      if (v === null || v === undefined) return
      if (f.fieldType === 'DATE') values[f.fieldKey] = dayjs(String(v))
      else if (f.fieldType === 'BOOLEAN') values[f.fieldKey] = v === 1 || v === true
      else values[f.fieldKey] = v
    })
    form.setFieldsValue(values)
    setModalOpen(true)
  }

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const save = async () => {
    if (!selectedKey || !meta) return
    const raw = await form.validateFields()
    const body: Record<string, unknown> = {}
    meta.fields.forEach((f) => {
      const v = raw[f.fieldKey]
      if (v === undefined || v === null) return
      if (f.fieldType === 'DATE') body[f.fieldKey] = (v as Dayjs).format('YYYY-MM-DDTHH:mm:ss')
      else if (f.fieldType === 'BOOLEAN') body[f.fieldKey] = v ? 1 : 0
      else body[f.fieldKey] = v
    })
    setSaving(true)
    try {
      if (editing) {
        await updateRecord(selectedKey, editing.id, body)
        message.success('已更新')
      } else {
        await createRecord(selectedKey, body)
        message.success('已创建')
      }
      setModalOpen(false)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (id: number) => {
    if (!selectedKey) return
    try {
      await deleteRecord(selectedKey, id)
      message.success('已删除')
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const applyFilter = () => {
    if (!draft || !draft.field || !draft.value) return
    setFilters((prev) => [...prev, `${draft.field}:${draft.op}:${draft.value}`])
    setDraft(null)
    setPage(1)
  }

  const renderFormInput = (fieldType: string, maxLength?: number | null) => {
    switch (fieldType) {
      case 'NUMBER':
        return <InputNumber style={{ width: '100%' }} />
      case 'DATE':
        return <DatePicker showTime style={{ width: '100%' }} />
      case 'BOOLEAN':
        return <Switch />
      case 'REFERENCE':
        return <InputNumber style={{ width: '100%' }} placeholder="被引记录 id" min={1} />
      default:
        return <Input maxLength={maxLength ?? undefined} placeholder={maxLength ? `≤ ${maxLength} 字符` : undefined} />
    }
  }

  return (
    <Card
      title={<Typography.Text strong>动态对象数据</Typography.Text>}
      extra={
        <Space>
          <Select style={{ width: 220 }} placeholder="选择已发布对象" value={selectedKey}
            onChange={(k) => {
              setSelectedKey(k)
              setPage(1); setFilters([]); setSort(undefined)
            }}
            options={objects.map((o) => ({ value: o.objectKey, label: `${o.displayName}（${o.objectKey}）` }))} />
          <Button type="primary" icon={<PlusOutlined />} disabled={!canCreate} onClick={openCreate}>新建记录</Button>
          <Button icon={<ReloadOutlined />} onClick={load} />
        </Space>
      }
    >
      {meta && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          {meta.displayName} → 物理表 <Typography.Text code>{meta.tableName}</Typography.Text>
          （v{meta.version}，{meta.fields.length} 个字段）
        </Typography.Paragraph>
      )}

      <Space wrap style={{ marginBottom: 12 }}>
        <Select style={{ width: 160 }} placeholder="过滤字段" value={draft?.field}
          options={meta?.fields.map((f) => ({ value: f.fieldKey, label: f.displayName })) ?? []}
          onChange={(v) => setDraft((d) => ({ field: v, op: d?.op ?? 'eq', value: d?.value ?? '' }))} />
        <Select style={{ width: 100 }} value={draft?.op ?? 'eq'}
          options={[
            { value: 'eq', label: '等于' },
            { value: 'like', label: '包含' },
            { value: 'in', label: '属于' },
          ]}
          disabled={draft ? (fieldDef(draft.field)?.fieldType ?? 'STRING') !== 'STRING' && draft.op === 'like' : false}
          onChange={(v) => setDraft((d) => ({ field: d?.field ?? '', op: v as FilterDraft['op'], value: d?.value ?? '' }))} />
        <Input style={{ width: 200 }} placeholder="值（in 用逗号分隔）" value={draft?.value}
          onChange={(e) => setDraft((d) => ({ field: d?.field ?? '', op: d?.op ?? 'eq', value: e.target.value }))} />
        <Button icon={<SearchOutlined />} onClick={applyFilter}>过滤</Button>
        {filters.map((f, i) => (
          <Tag key={f + i} closable onClose={() => setFilters((prev) => prev.filter((_, j) => j !== i))}>
            {f}
          </Tag>
        ))}
      </Space>

      <Table rowKey="id" size="middle" loading={loading} dataSource={rows} columns={columns}
        pagination={{ current: page, pageSize, total, showSizeChanger: false }}
        onChange={(p, _f, sorter) => {
          setPage(p.current ?? 1)
          const s = Array.isArray(sorter) ? sorter[0] : sorter
          if (s?.order) setSort(`${s.order === 'descend' ? '-' : ''}${String(s.field)}`)
          else setSort(undefined)
        }} />

      <Modal title={editing ? `编辑记录 #${editing.id}` : '新建记录'} open={modalOpen}
        onCancel={() => setModalOpen(false)} onOk={save} confirmLoading={saving} okButtonProps={{ disabled: editing ? !canUpdate : !canCreate }}>
        <Form form={form} layout="vertical">
          {(formLayout
            ? formLayout.fields.filter((l) => l.visible)
                .map((l) => ({ f: meta?.fields.find((x) => x.fieldKey === l.fieldKey), l }))
                .filter((x) => !!x.f)
            : (meta?.fields ?? []).map((f) => ({ f, l: undefined as undefined | { label?: string | null } }))
          ).map(({ f, l }) => (
            <Form.Item key={f!.fieldKey} name={f!.fieldKey}
              label={`${l?.label || f!.displayName}（${f!.fieldKey}）`}
              rules={f!.required && !editing ? [{ required: true, message: '必填' }] : []}
              valuePropName={f!.fieldType === 'BOOLEAN' ? 'checked' : 'value'}>
              {renderFormInput(f!.fieldType, f!.maxLength)}
            </Form.Item>
          ))}
        </Form>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          类型映射：{FIELD_TYPES.map((t) => t.label).join(' / ')}；引用字段填被引对象记录 id。
        </Typography.Text>
      </Modal>
    </Card>
  )
}
