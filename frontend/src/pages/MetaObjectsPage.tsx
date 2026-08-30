import { useCallback, useEffect, useState } from 'react'
import {
  Button, Card, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select, Space,
  Switch, Table, Tag, theme, Typography, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { CodeOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import {
  FIELD_TYPES, createMetaObject, fetchMetaObject, fetchMetaObjects, previewDdl,
  publishMetaObject, updateMetaObject,
  type FieldRequest, type FieldType, type MetaFieldDef, type MetaObjectDetail, type MetaObjectSummary,
} from '../api/metadata'
import { usePerm } from '../perm/PermContext'

/** 编辑态字段行（表内受控编辑） */
interface EditableField {
  key: number
  fieldKey: string
  displayName: string
  fieldType: FieldType
  required: boolean
  maxLength: number | null
  refObject: string
  refField: string
}

let rowSeq = 0

function toEditable(f: MetaFieldDef): EditableField {
  return {
    key: f.id ?? --rowSeq,
    fieldKey: f.fieldKey,
    displayName: f.displayName,
    fieldType: f.fieldType,
    required: f.required,
    maxLength: f.maxLength ?? null,
    refObject: f.refObject ?? '',
    refField: f.refField ?? '',
  }
}

/** 对象建模页（F2 设计 3）：定义业务对象 → 发布 → 自动获得 CRUD API/物理表/权限点 */
export default function MetaObjectsPage() {
  const { hasPerm } = usePerm()
  const { token } = theme.useToken()
  const canManage = hasPerm('meta:manage')
  const [data, setData] = useState<MetaObjectSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<MetaObjectDetail | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [ddl, setDdl] = useState<{ name: string; text: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<{ objectKey: string; displayName: string }>()
  const [fields, setFields] = useState<EditableField[]>([])
  const [objectKeys, setObjectKeys] = useState<string[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetchMetaObjects()
      setData(r.items)
      setObjectKeys(r.items.map((o) => o.objectKey))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setFields([{ key: --rowSeq, fieldKey: '', displayName: '', fieldType: 'STRING', required: false, maxLength: null, refObject: '', refField: '' }])
    setDrawerOpen(true)
  }

  const openEdit = async (id: number) => {
    try {
      const detail = await fetchMetaObject(id)
      setEditing(detail)
      form.setFieldsValue({ objectKey: detail.objectKey, displayName: detail.displayName })
      setFields(detail.fields.map(toEditable))
      setDrawerOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  const updateField = (key: number, patch: Partial<EditableField>) => {
    setFields((prev) => prev.map((f) => (f.key === key ? { ...f, ...patch } : f)))
  }

  const save = async () => {
    const values = await form.validateFields()
    if (fields.some((f) => !f.fieldKey || !f.displayName)) {
      message.warning('字段 Key 与显示名均不能为空')
      return
    }
    const dup = fields.map((f) => f.fieldKey).filter((k, i, arr) => arr.indexOf(k) !== i)
    if (dup.length > 0) {
      message.warning(`字段 Key 重复: ${[...new Set(dup)].join(', ')}`)
      return
    }
    const body: FieldRequest[] = fields.map((f) => ({
      fieldKey: f.fieldKey,
      displayName: f.displayName,
      fieldType: f.fieldType,
      required: f.required,
      maxLength: f.fieldType === 'STRING' ? f.maxLength : null,
      refObject: f.fieldType === 'REFERENCE' ? f.refObject : null,
      refField: f.fieldType === 'REFERENCE' && f.refField ? f.refField : null,
    }))
    setSaving(true)
    try {
      if (editing) {
        await updateMetaObject(editing.id, { displayName: values.displayName, fields: body })
        message.success('草稿已保存')
      } else {
        await createMetaObject({ objectKey: values.objectKey, displayName: values.displayName, fields: body })
        message.success('对象已创建（草稿）')
      }
      setDrawerOpen(false)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const publish = async (row: MetaObjectSummary) => {
    try {
      const r = await publishMetaObject(row.id)
      message.success(`已发布 ${r.tableName}（版本 v${r.version}）：CRUD API 与权限点已就绪`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '发布失败')
    }
  }

  const showDdl = async (row: MetaObjectSummary) => {
    try {
      const r = await previewDdl(row.id)
      setDdl({ name: r.tableName, text: r.ddl })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  const fieldColumns: ColumnsType<EditableField> = [
    {
      title: '字段 Key', dataIndex: 'fieldKey', width: 160,
      render: (_, f) => <Input value={f.fieldKey} placeholder="snake_case" disabled={!canManage}
        onChange={(e) => updateField(f.key, { fieldKey: e.target.value })} />,
    },
    {
      title: '显示名', dataIndex: 'displayName', width: 150,
      render: (_, f) => <Input value={f.displayName} disabled={!canManage}
        onChange={(e) => updateField(f.key, { displayName: e.target.value })} />,
    },
    {
      title: '类型', dataIndex: 'fieldType', width: 110,
      render: (_, f) => (
        <Select value={f.fieldType} style={{ width: '100%' }} disabled={!canManage}
          options={FIELD_TYPES} onChange={(v) => updateField(f.key, { fieldType: v })} />
      ),
    },
    {
      title: '必填', dataIndex: 'required', width: 70,
      render: (_, f) => <Switch checked={f.required} size="small" disabled={!canManage}
        onChange={(v) => updateField(f.key, { required: v })} />,
    },
    {
      title: '列宽', dataIndex: 'maxLength', width: 110,
      render: (_, f) => (
        <InputNumber value={f.maxLength ?? undefined} min={1} max={4000} placeholder="255" style={{ width: '100%' }}
          disabled={!canManage || f.fieldType !== 'STRING'}
          onChange={(v) => updateField(f.key, { maxLength: v ?? null })} />
      ),
    },
    {
      title: '引用对象', dataIndex: 'refObject', width: 150,
      render: (_, f) => (
        <Select value={f.refObject || undefined} placeholder="objectKey" style={{ width: '100%' }} allowClear
          disabled={!canManage || f.fieldType !== 'REFERENCE'}
          options={[
            ...(editing ? [{ value: editing.objectKey, label: `${editing.objectKey}（自身）` }] : []),
            ...objectKeys.filter((k) => k !== editing?.objectKey).map((k) => ({ value: k, label: k })),
          ]}
          onChange={(v) => updateField(f.key, { refObject: v ?? '' })} />
      ),
    },
    {
      title: '展示字段', dataIndex: 'refField', width: 120,
      render: (_, f) => (
        <Input value={f.refField} placeholder="id" disabled={!canManage || f.fieldType !== 'REFERENCE'}
          onChange={(e) => updateField(f.key, { refField: e.target.value })} />
      ),
    },
    {
      title: '', width: 44,
      render: (_, f) => (
        <Button type="text" danger icon={<DeleteOutlined />} disabled={!canManage || fields.length <= 1}
          onClick={() => setFields((prev) => prev.filter((x) => x.key !== f.key))} />
      ),
    },
  ]

  const columns: ColumnsType<MetaObjectSummary> = [
    { title: '对象 Key', dataIndex: 'objectKey', width: 150, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '显示名', dataIndex: 'displayName', width: 150 },
    { title: '物理表', dataIndex: 'tableName', width: 170, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => <Tag color={s === 'PUBLISHED' ? 'green' : 'orange'}>{s === 'PUBLISHED' ? '已发布' : '草稿'}</Tag> },
    { title: '版本', dataIndex: 'version', width: 70 },
    { title: '字段数', dataIndex: 'fieldCount', width: 80 },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => (v ?? '').replace('T', ' ').slice(0, 19) },
    {
      title: '操作', width: 260,
      render: (_, row) => (
        <Space>
          {row.status === 'DRAFT' && (
            <>
              <Button size="small" disabled={!canManage} onClick={() => openEdit(row.id)}>编辑</Button>
              <Popconfirm
                title="发布该对象？"
                description="将生成并执行建表 DDL、创建四个权限点并同步 AI 知识库。"
                onConfirm={() => publish(row)}
                disabled={!canManage}
              >
                <Button size="small" type="primary" icon={<RocketOutlined />} disabled={!canManage}>发布</Button>
              </Popconfirm>
            </>
          )}
          <Button size="small" icon={<CodeOutlined />} onClick={() => showDdl(row)}>DDL</Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>对象建模</Typography.Text>}
      extra={
        <Space>
          <Button type="primary" icon={<PlusOutlined />} disabled={!canManage} onClick={openCreate}>
            新建对象
          </Button>
          <Button icon={<ReloadOutlined />} onClick={load} />
        </Space>
      }
    >
      <Table rowKey="id" size="middle" loading={loading} dataSource={data} columns={columns}
        pagination={{ pageSize: 15, showSizeChanger: false }} />

      <Drawer
        title={editing ? `编辑对象：${editing.displayName}（草稿）` : '新建对象'}
        width={1000} open={drawerOpen} onClose={() => setDrawerOpen(false)}
        extra={<Button type="primary" loading={saving} disabled={!canManage} onClick={save}>保存草稿</Button>}
      >
        <Form form={form} layout="vertical">
          <Space size="large" style={{ display: 'flex', marginBottom: 16 }}>
            <Form.Item name="objectKey" label="对象 Key（API 路径段与表名后缀）"
              rules={[
                { required: true, message: '必填' },
                { pattern: /^[a-z][a-z0-9_]{2,40}$/, message: '小写字母开头，仅小写字母/数字/下划线，3~41 位' },
              ]}
              style={{ minWidth: 260, marginBottom: 0 }}>
              <Input placeholder="如 equipment" disabled={!!editing} />
            </Form.Item>
            <Form.Item name="displayName" label="显示名" rules={[{ required: true, message: '必填' }]}
              style={{ minWidth: 220, marginBottom: 0 }}>
              <Input placeholder="如 设备台账" />
            </Form.Item>
          </Space>
        </Form>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          字段定义（{fields.length} 个）——STRING→VARCHAR / NUMBER→NUMERIC / DATE→TIMESTAMP / BOOLEAN→SMALLINT / REFERENCE→BIGINT
        </Typography.Text>
        <Table rowKey="key" size="small" dataSource={fields} columns={fieldColumns} pagination={false}
          footer={() => (
            <Button block type="dashed" icon={<PlusOutlined />} disabled={!canManage}
              onClick={() => setFields((prev) => [...prev, {
                key: --rowSeq, fieldKey: '', displayName: '', fieldType: 'STRING',
                required: false, maxLength: null, refObject: '', refField: '',
              }])}>
              添加字段
            </Button>
          )} />
      </Drawer>

      <Modal title={<span>DDL 预览：<Typography.Text code>{ddl?.name}</Typography.Text></span>}
        open={!!ddl} footer={null} onCancel={() => setDdl(null)} width={760}>
        <pre style={{ maxHeight: 480, overflow: 'auto', background: token.colorFillQuaternary, padding: 12, fontSize: 12 }}>
          {ddl?.text}
        </pre>
      </Modal>
    </Card>
  )
}
