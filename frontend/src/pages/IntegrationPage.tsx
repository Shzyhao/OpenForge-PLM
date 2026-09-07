import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Select, Space, Table,
  Tabs, Tag, theme, Typography, message,
} from 'antd'
import {
  DeleteOutlined, PlusOutlined, ReloadOutlined, RocketOutlined, SendOutlined,
} from '@ant-design/icons'
import {
  CONN_TYPES, createConnector, createCredential, deleteConnector, deleteCredential, disableConnector,
  fetchConnector, fetchConnectors, fetchCredentials, fetchExecLogs, publishConnector, testConnector,
  updateConnector,
  type ConnSummary, type ConnType, type Credential, type ExecLog, type InvokeResult, type PageData,
} from '../api/connector'
import ConnectorConfigPanel, { type ConnectorConfigPanelHandle } from '../components/ConnectorConfigPanel'
import { usePerm } from '../perm/PermContext'

/** 连接器状态标签色（集成编排器 MVP 设计 §8） */
const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'default', PUBLISHED: 'green', DISABLED: 'red',
}

interface SpecDraft {
  connCode: string
  connName: string
  connType: ConnType
  description: string
  spec: Record<string, unknown>
}

const EMPTY_DRAFT = (connType: ConnType): SpecDraft => ({
  connCode: '', connName: '', connType, description: '', spec: {},
})

export default function IntegrationPage() {
  const { token } = theme.useToken()
  const { hasPerm } = usePerm()
  const canManage = hasPerm('conn:manage')

  const [data, setData] = useState<ConnSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [credentials, setCredentials] = useState<Credential[]>([])

  // 配置抽屉（NodeConfigPanel 承载全部表单；P3 画布直接复用）
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editing, setEditing] = useState<ConnSummary | null>(null)
  const [draft, setDraft] = useState<SpecDraft>(EMPTY_DRAFT('HTTP_REST'))
  const [saving, setSaving] = useState(false)
  const panelRef = useRef<ConnectorConfigPanelHandle>(null)
  const [specForm] = Form.useForm<{ connCode: string; connName: string; description: string }>()

  // 试运行面板
  const [testing, setTesting] = useState<ConnSummary | null>(null)
  const [paramText, setParamText] = useState('{}')
  const [testingBusy, setTestingBusy] = useState(false)
  const [testResult, setTestResult] = useState<InvokeResult | null>(null)

  // 执行日志
  const [logsFor, setLogsFor] = useState<ConnSummary | null>(null)
  const [logs, setLogs] = useState<ExecLog[]>([])
  const [logsLoading, setLogsLoading] = useState(false)

  // 凭据
  const [credModalOpen, setCredModalOpen] = useState(false)
  const [credSaving, setCredSaving] = useState(false)
  const [credForm] = Form.useForm<{
    credCode: string; credName: string; authType: string; secret: string; extra: string
  }>()

  const load = useCallback(async (targetPage = page) => {
    setLoading(true)
    try {
      const result: PageData<ConnSummary> = await fetchConnectors(targetPage, 15)
      setData(result.list)
      setTotal(result.total)
      setPage(result.page)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page])

  const loadCredentials = useCallback(async () => {
    try {
      const result = await fetchCredentials(1, 100)
      setCredentials(result.list)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '凭据加载失败')
    }
  }, [])

  useEffect(() => { void load(1) /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])
  useEffect(() => { void loadCredentials() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  const openCreate = () => {
    setEditing(null)
    setDraft(EMPTY_DRAFT('HTTP_REST'))
    specForm.resetFields()
    setDrawerOpen(true)
  }

  const openEdit = async (row: ConnSummary) => {
    try {
      const detail = await fetchConnector(row.id)
      setEditing(row)
      setDraft({
        connCode: detail.connCode,
        connName: detail.connName,
        connType: detail.connType,
        description: detail.description ?? '',
        spec: detail.spec,
      })
      specForm.setFieldsValue({
        connCode: detail.connCode, connName: detail.connName, description: detail.description ?? '',
      })
      setDrawerOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  const save = async () => {
    const basic = await specForm.validateFields()
    const spec = await panelRef.current?.collect()
    if (!spec) return
    setSaving(true)
    try {
      if (editing) {
        await updateConnector(editing.id, {
          connName: basic.connName, connType: draft.connType,
          description: basic.description, spec,
        })
        message.success('已保存')
      } else {
        await createConnector({
          connCode: basic.connCode, connName: basic.connName, connType: draft.connType,
          description: basic.description, spec,
        })
        message.success('连接器已创建（草稿）')
      }
      setDrawerOpen(false)
      await load(1)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const publish = async (row: ConnSummary) => {
    try {
      const r = await publishConnector(row.id)
      message.success(`已发布（版本 v${r.version}）：invoke API 立即可用`)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '发布失败')
    }
  }

  const disable = async (row: ConnSummary) => {
    try {
      await disableConnector(row.id)
      message.success('已停用')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '停用失败')
    }
  }

  const remove = async (row: ConnSummary) => {
    try {
      await deleteConnector(row.id)
      message.success('已删除')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const openTest = (row: ConnSummary) => {
    setTesting(row)
    setTestResult(null)
    setParamText('{}')
  }

  const runTest = async () => {
    if (!testing) return
    let params: Record<string, unknown>
    try {
      params = paramText.trim() ? JSON.parse(paramText) : {}
    } catch {
      message.warning('参数须为合法 JSON')
      return
    }
    setTestingBusy(true)
    try {
      setTestResult(await testConnector(testing.id, params))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '试运行失败')
    } finally {
      setTestingBusy(false)
    }
  }

  const openLogs = async (row: ConnSummary) => {
    setLogsFor(row)
    setLogsLoading(true)
    try {
      const result = await fetchExecLogs(row.id, 1, 20)
      setLogs(result.list)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '日志加载失败')
    } finally {
      setLogsLoading(false)
    }
  }

  const saveCredential = async () => {
    const values = await credForm.validateFields()
    setCredSaving(true)
    try {
      await createCredential({
        credCode: values.credCode, credName: values.credName, authType: values.authType,
        secret: values.secret, extra: values.extra || undefined,
      })
      message.success('凭据已创建（密文落库，不回显）')
      setCredModalOpen(false)
      credForm.resetFields()
      await loadCredentials()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '凭据创建失败')
    } finally {
      setCredSaving(false)
    }
  }

  const columns = [
    { title: 'Code', dataIndex: 'connCode', width: 150 },
    { title: '名称', dataIndex: 'connName', width: 160 },
    {
      title: '类型', dataIndex: 'connType', width: 130,
      render: (t: string) => CONN_TYPES.find((c) => c.value === t)?.label ?? t,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => <Tag color={STATUS_COLORS[s]}>{s}</Tag>,
    },
    { title: '版本', dataIndex: 'currentVersion', width: 70, render: (v: number) => `v${v}` },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '操作', width: 340,
      render: (_: unknown, row: ConnSummary) => (
        <Space>
          {row.status !== 'PUBLISHED' && (
            <Button size="small" disabled={!canManage} onClick={() => openEdit(row)}>编辑</Button>
          )}
          {row.status !== 'PUBLISHED' && (
            <Popconfirm title="发布该连接器？" description="生成不可变版本快照并开放 invoke 调用。"
              onConfirm={() => publish(row)} disabled={!canManage}>
              <Button size="small" type="primary" icon={<RocketOutlined />} disabled={!canManage}>发布</Button>
            </Popconfirm>
          )}
          {row.status === 'PUBLISHED' && (
            <Popconfirm title="停用后 invoke 立即失败，确定？" onConfirm={() => disable(row)} disabled={!canManage}>
              <Button size="small" danger disabled={!canManage}>停用</Button>
            </Popconfirm>
          )}
          <Button size="small" icon={<SendOutlined />} onClick={() => openTest(row)}>测试</Button>
          <Button size="small" onClick={() => openLogs(row)}>日志</Button>
          {row.status !== 'PUBLISHED' && (
            <Popconfirm title="删除该连接器？" onConfirm={() => remove(row)} disabled={!canManage}>
              <Button size="small" danger icon={<DeleteOutlined />} disabled={!canManage} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>集成编排器</Typography.Text>}
      extra={
        <Space>
          <Button type="primary" icon={<PlusOutlined />} disabled={!canManage} onClick={openCreate}>
            新建连接器
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => load(1)} />
        </Space>
      }
    >
      <Tabs defaultActiveKey="connectors" items={[
        {
          key: 'connectors', label: '连接器',
          children: (
            <Table rowKey="id" size="middle" loading={loading} dataSource={data} columns={columns}
              pagination={{
                current: page, total, pageSize: 15, showSizeChanger: false,
                onChange: (p) => load(p),
              }} />
          ),
        },
        {
          key: 'credentials', label: '凭据库',
          children: (
            <Card title={<Typography.Text strong>凭据（密文落库，仅写不读）</Typography.Text>}
              extra={
                <Button type="primary" size="small" icon={<PlusOutlined />} disabled={!canManage}
                  onClick={() => setCredModalOpen(true)}>新建凭据</Button>
              } styles={{ body: { paddingInline: 0 } }}>
              <Table rowKey="id" size="small" dataSource={credentials}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                columns={[
                  { title: 'Code', dataIndex: 'credCode', width: 160 },
                  { title: '名称', dataIndex: 'credName', width: 180 },
                  { title: '认证类型', dataIndex: 'authType', width: 160 },
                  {
                    title: '附加信息', dataIndex: 'extra', ellipsis: true,
                    render: (v: string | null) => v ? <Typography.Text code>{v}</Typography.Text> : '-',
                  },
                  {
                    title: '操作', width: 90,
                    render: (_: unknown, row: Credential) => (
                      <Popconfirm title="删除凭据？引用它的连接器将无法发布。" onConfirm={() => {
                        void deleteCredential(row.id).then(loadCredentials)
                          .catch((e: unknown) => message.error(e instanceof Error ? e.message : '删除失败'))
                      }} disabled={!canManage}>
                        <Button size="small" danger icon={<DeleteOutlined />} disabled={!canManage} />
                      </Popconfirm>
                    ),
                  },
                ]} />
            </Card>
          ),
        },
      ]} />

      <Drawer
        title={editing ? `编辑连接器：${editing.connName}` : '新建连接器'}
        width={760} open={drawerOpen} onClose={() => setDrawerOpen(false)}
        extra={<Button type="primary" loading={saving} disabled={!canManage} onClick={save}>保存</Button>}
        destroyOnClose
      >
        <Form form={specForm} layout="vertical">
          <Space size="large" style={{ display: 'flex' }} wrap>
            <Form.Item name="connCode" label="Code（租户内唯一，invoke 路径段）"
              rules={[
                { required: true, message: '必填' },
                { pattern: /^[a-z][a-z0-9_]{2,63}$/, message: '小写字母开头，仅小写字母/数字/下划线，3~64 位' },
              ]}
              style={{ marginBottom: 0 }}>
              <Input placeholder="如 erp_stock" disabled={!!editing} style={{ width: 240 }} />
            </Form.Item>
            <Form.Item name="connName" label="名称" rules={[{ required: true, message: '必填' }]}
              style={{ marginBottom: 0 }}>
              <Input placeholder="如 ERP 库存查询" style={{ width: 200 }} />
            </Form.Item>
          </Space>
          <Space size="large" style={{ display: 'flex', marginTop: 12 }} wrap>
            <Form.Item label="类型" style={{ marginBottom: 0 }}>
              <Select style={{ width: 200 }} value={draft.connType} disabled={!!editing}
                onChange={(t) => setDraft((d) => ({ ...d, connType: t }))}
                options={CONN_TYPES} />
            </Form.Item>
            <Form.Item name="description" label="描述" style={{ marginBottom: 0, minWidth: 320 }}>
              <Input placeholder="用途说明（可选）" />
            </Form.Item>
          </Space>
        </Form>
        <div style={{ marginTop: 20 }}>
          <ConnectorConfigPanel
            ref={panelRef} connType={draft.connType}
            spec={editing && draft.connType === editing.connType ? draft.spec : {}}
            credentials={credentials} />
        </div>
      </Drawer>

      <Modal
        title={`试运行：${testing?.connName ?? ''}`}
        open={!!testing} onCancel={() => setTesting(null)} footer={null} width={680}>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          参数（JSON 对象；必填参数须齐备）
        </Typography.Text>
        <Input.TextArea rows={4} value={paramText} onChange={(e) => setParamText(e.target.value)}
          style={{ fontFamily: 'monospace' }} />
        <Button type="primary" icon={<SendOutlined />} loading={testingBusy}
          onClick={runTest} style={{ marginTop: 12 }}>执行</Button>
        {testResult && (
          <Descriptions size="small" column={2} style={{ marginTop: 16 }} bordered>
            <Descriptions.Item label="状态">
              <Tag color={testResult.status === 'SUCCESS' ? 'green' : 'red'}>{testResult.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="耗时">{testResult.durationMs}ms</Descriptions.Item>
            <Descriptions.Item label="HTTP">{testResult.httpStatus ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="行数">{testResult.rowsReturned ?? '-'}</Descriptions.Item>
          </Descriptions>
        )}
        {testResult?.body && (
          <pre style={{
            maxHeight: 280, overflow: 'auto', marginTop: 12, padding: 12, fontSize: 12,
            background: token.colorFillQuaternary,
          }}>{testResult.body}</pre>
        )}
        {testResult?.error && (
          <Typography.Text type="danger" style={{ display: 'block', marginTop: 12 }}>
            {testResult.error}
          </Typography.Text>
        )}
      </Modal>

      <Modal
        title={`执行日志：${logsFor?.connName ?? ''}`}
        open={!!logsFor} onCancel={() => setLogsFor(null)} footer={null} width={860}>
        <Table rowKey="id" size="small" loading={logsLoading} dataSource={logs}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 165 },
            { title: '触发', dataIndex: 'triggerType', width: 80 },
            {
              title: '状态', dataIndex: 'status', width: 95,
              render: (s: string) => (
                <Tag color={s === 'SUCCESS' ? 'green' : s === 'BLOCKED' ? 'orange' : 'red'}>{s}</Tag>
              ),
            },
            { title: 'HTTP', dataIndex: 'httpStatus', width: 70, render: (v: number | null) => v ?? '-' },
            { title: '行数', dataIndex: 'rowsReturned', width: 70, render: (v: number | null) => v ?? '-' },
            { title: '耗时(ms)', dataIndex: 'durationMs', width: 90 },
            { title: '错误', dataIndex: 'errorMsg', ellipsis: true },
          ]} />
      </Modal>

      <Modal
        title="新建凭据" open={credModalOpen} onCancel={() => setCredModalOpen(false)}
        onOk={saveCredential} confirmLoading={credSaving} destroyOnClose>
        <Form form={credForm} layout="vertical">
          <Form.Item name="credCode" label="Code"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[a-z][a-z0-9_]{2,63}$/, message: '小写字母开头，仅小写字母/数字/下划线，3~64 位' },
            ]}>
            <Input placeholder="如 cred_erp_main" />
          </Form.Item>
          <Form.Item name="credName" label="名称" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 ERP 主凭据" />
          </Form.Item>
          <Form.Item name="authType" label="认证类型" rules={[{ required: true, message: '必填' }]}>
            <Select options={[
              { value: 'BEARER', label: 'Bearer Token' },
              { value: 'BASIC', label: 'Basic（填 user:password）' },
              { value: 'API_KEY_HEADER', label: 'API Key（自定义头）' },
              { value: 'JDBC_PASSWORD', label: '数据库密码（JDBC 连接器用）' },
            ]} />
          </Form.Item>
          <Form.Item name="secret" label="凭据值（保存后不可查看，仅可覆盖）"
            rules={[{ required: true, message: '必填' }]}>
            <Input.Password placeholder="Token / user:password / 密码" autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="extra" label={'附加信息 JSON（如 {"headerName":"X-Api-Key"}，可选）'}>
            <Input placeholder='{"headerName":"X-Api-Key"}' style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
