import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select, Space,
  Table, Tabs, Tag, theme, Typography, message,
} from 'antd'
import {
  DeleteOutlined, PlusOutlined, ReloadOutlined, RocketOutlined, SendOutlined,
} from '@ant-design/icons'
import {
  CONN_TYPES, EVENT_TOPICS, TRIGGER_TYPES, createAiProvider, createConnector, createCredential,
  deleteAiProvider, deleteConnector, deleteCredential, disableConnector, discardDlq, fetchAiProviders,
  fetchConnector, fetchConnectors, fetchCredentials, fetchDlq, fetchExecLogs, publishConnector,
  replayDlq, testAiProvider, testConnector, updateAiProvider, updateConnector,
  type AiProvider, type ConnSummary, type ConnType, type Credential, type DlqRecord, type ExecLog,
  type InvokeResult, type PageData, type TriggerForm, type TriggerType,
} from '../api/connector'
import ConnectorConfigPanel, { type ConnectorConfigPanelHandle } from '../components/ConnectorConfigPanel'
import FlowDesigner from '../components/FlowDesigner'
import { ORCHESTRATION_NODE_TYPES } from '../flow/nodeTypes'
import { chainFlowWithLayout, emptyChainFlow, flowToChain } from '../flow/orchestration'
import type { FlowDef } from '../flow/flowModel'
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
  triggerType: TriggerType
  trigger: TriggerForm
}

const EMPTY_DRAFT = (connType: ConnType): SpecDraft => ({
  connCode: '', connName: '', connType, description: '', spec: {},
  triggerType: 'NONE', trigger: {},
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
  const [newType, setNewType] = useState<ConnType>('HTTP_REST')
  const [triggerParamsText, setTriggerParamsText] = useState('{}')
  const [saving, setSaving] = useState(false)
  const panelRef = useRef<ConnectorConfigPanelHandle>(null)
  const [specForm] = Form.useForm<{ connCode: string; connName: string; description: string }>()

  // 触发死信（P2-2 §12.2）
  const [dlq, setDlq] = useState<DlqRecord[]>([])
  const [dlqTotal, setDlqTotal] = useState(0)
  const [dlqPage, setDlqPage] = useState(1)
  const [dlqStatus, setDlqStatus] = useState<string>('PENDING')
  const [dlqLoading, setDlqLoading] = useState(false)
  const [dlqBusyId, setDlqBusyId] = useState<number | null>(null)

  // 编排画布（P3 刀3）：CHAIN 连接器的链编辑 + 整链试运行
  const [chainConn, setChainConn] = useState<ConnSummary | null>(null)
  const [chainFlow, setChainFlow] = useState<FlowDef>(emptyChainFlow())
  const [chainSaving, setChainSaving] = useState(false)
  const [chainTestOpen, setChainTestOpen] = useState(false)
  const [chainTestParams, setChainTestParams] = useState('{}')
  const [chainTestBusy, setChainTestBusy] = useState(false)
  const [chainTestResult, setChainTestResult] = useState<InvokeResult | null>(null)

  // 试运行面板
  const [testing, setTesting] = useState<ConnSummary | null>(null)
  const [paramText, setParamText] = useState('{}')
  const [testingBusy, setTestingBusy] = useState(false)
  const [testResult, setTestResult] = useState<InvokeResult | null>(null)

  // 执行日志
  const [logsFor, setLogsFor] = useState<ConnSummary | null>(null)
  const [logs, setLogs] = useState<ExecLog[]>([])
  const [logsLoading, setLogsLoading] = useState(false)

  // AI 模型（P2-1 AI API 配置器）
  const [providers, setProviders] = useState<AiProvider[]>([])
  const [provModalOpen, setProvModalOpen] = useState(false)
  const [provSaving, setProvSaving] = useState(false)
  const [provForm] = Form.useForm<{
    providerCode: string; providerName: string; baseUrl: string; apiKey: string
    model: string; timeoutMs: number; priority: number
  }>()
  const [provTestBusyId, setProvTestBusyId] = useState<number | null>(null)

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

  const loadDlq = useCallback(async (targetPage = 1, status = dlqStatus) => {
    setDlqLoading(true)
    try {
      const result = await fetchDlq(status || undefined, targetPage, 15)
      setDlq(result.list)
      setDlqTotal(result.total)
      setDlqPage(result.page)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '死信加载失败')
    } finally {
      setDlqLoading(false)
    }
  }, [dlqStatus])

  useEffect(() => { void load(1) /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])
  useEffect(() => { void loadCredentials() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  const loadProviders = useCallback(async () => {
    try {
      const result = await fetchAiProviders(1, 50)
      setProviders(result.list)
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'AI 模型加载失败')
    }
  }, [])
  useEffect(() => { void loadProviders() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [])

  const openCreate = () => {
    setEditing(null)
    setDraft(EMPTY_DRAFT('HTTP_REST'))
    setTriggerParamsText('{}')
    specForm.resetFields()
    setNewType('HTTP_REST')
    setDrawerOpen(true)
  }

  /** 新建保存：CHAIN 类型走骨架创建 + 编排画布，其余走配置抽屉。 */
  const saveNew = async () => {
    const basic = await specForm.validateFields()
    if (newType === 'CHAIN') {
      setSaving(true)
      try {
        await createChain(basic.connCode, basic.connName)
        setDrawerOpen(false)
      } catch (e) {
        message.error(e instanceof Error ? e.message : '创建失败')
      } finally {
        setSaving(false)
      }
      return
    }
    await save()
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
        triggerType: detail.triggerType ?? 'NONE',
        trigger: detail.trigger ?? {},
      })
      specForm.setFieldsValue({
        connCode: detail.connCode, connName: detail.connName, description: detail.description ?? '',
      })
      setTriggerParamsText(detail.trigger?.params && Object.keys(detail.trigger.params).length
        ? JSON.stringify(detail.trigger.params, null, 2) : '{}')
      setDrawerOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  const save = async () => {
    const basic = await specForm.validateFields()
    const spec = await panelRef.current?.collect()
    if (!spec) return
    // 触发入参 JSON 校验（EVENT/CRON 才需要）
    let triggerParams: Record<string, unknown> | undefined
    if (draft.triggerType !== 'NONE' && triggerParamsText.trim()) {
      try {
        triggerParams = JSON.parse(triggerParamsText)
      } catch {
        message.warning('触发参数须为合法 JSON 对象')
        return
      }
    }
    const trigger: TriggerForm | undefined = draft.triggerType === 'NONE' ? undefined
      : draft.triggerType === 'EVENT'
        ? { ...draft.trigger, params: triggerParams ?? {} }
        : { ...draft.trigger, params: triggerParams ?? {} }
    setSaving(true)
    try {
      if (editing) {
        await updateConnector(editing.id, {
          connName: basic.connName, connType: draft.connType,
          description: basic.description, spec,
          triggerType: draft.triggerType, trigger,
        })
        message.success('已保存')
      } else {
        await createConnector({
          connCode: basic.connCode, connName: basic.connName, connType: draft.connType,
          description: basic.description, spec,
          triggerType: draft.triggerType, trigger,
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

  const saveProvider = async () => {
    const v = await provForm.validateFields()
    setProvSaving(true)
    try {
      await createAiProvider({
        providerCode: v.providerCode, providerName: v.providerName, baseUrl: v.baseUrl,
        apiKey: v.apiKey, model: v.model, timeoutMs: v.timeoutMs || undefined,
        priority: v.priority ?? undefined, enabled: 1,
      })
      message.success('AI 供应商已创建（key 密文落库）')
      setProvModalOpen(false)
      provForm.resetFields()
      await loadProviders()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      setProvSaving(false)
    }
  }

  const testProvider = async (row: AiProvider) => {
    setProvTestBusyId(row.id)
    try {
      const r = await testAiProvider(row.id)
      if (r.status === 'SUCCESS') {
        message.success(`连通正常（${r.httpStatus}，${r.durationMs}ms）`)
      } else {
        message.warning(`连通失败：${r.error ?? '未知错误'}`)
      }
      await loadProviders()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '测试失败')
    } finally {
      setProvTestBusyId(null)
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

  const replayDlqRow = async (row: DlqRecord) => {
    setDlqBusyId(row.id)
    try {
      const r = await replayDlq(row.id)
      if (r.status === 'SUCCESS') {
        message.success(`重放成功（${row.connCode}），已标记 RESOLVED`)
      } else {
        message.warning(`重放仍失败（第 ${r.retryCount} 次），保持 PENDING 待处理`)
      }
      await loadDlq(dlqPage)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重放失败')
    } finally {
      setDlqBusyId(null)
    }
  }

  const discardDlqRow = async (row: DlqRecord) => {
    setDlqBusyId(row.id)
    try {
      await discardDlq(row.id)
      message.success('已丢弃（保留记录供追溯）')
      await loadDlq(dlqPage)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '丢弃失败')
    } finally {
      setDlqBusyId(null)
    }
  }

  // ===== 编排画布（P3 刀3）=====

  const openChainEditor = async (row: ConnSummary) => {
    try {
      const detail = await fetchConnector(row.id)
      setChainConn(row)
      setChainFlow(chainFlowWithLayout(detail.spec as Record<string, unknown>))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    }
  }

  /** 新建编排链：先落一个最小合法链骨架（s1 占位步骤），随即进入画布编辑。 */
  const createChain = async (code: string, name: string) => {
    const spec = {
      schemaVersion: 2,
      steps: [{
        key: 's1', type: 'HTTP_REST',
        spec: { schemaVersion: 1, method: 'GET', url: 'http://localhost:8080/actuator/health' },
      }],
    }
    const resp = await createConnector({
      connCode: code, connName: name, connType: 'CHAIN',
      description: '编排链', spec: spec as unknown as Record<string, unknown>,
    })
    message.success('编排链已创建（骨架）——请编辑步骤后发布')
    const row: ConnSummary = {
      id: resp.id, connCode: resp.connCode, connName: resp.connName,
      connType: 'CHAIN', status: resp.status, currentVersion: resp.currentVersion,
      description: resp.description ?? null, triggerType: resp.triggerType ?? 'NONE',
      updatedAt: resp.updatedAt ?? null,
    }
    setChainConn(row)
    setChainFlow(chainFlowWithLayout(spec))
  }

  const saveChain = async () => {
    if (!chainConn) return
    let spec: Record<string, unknown>
    try {
      spec = flowToChain(chainFlow)
    } catch (e) {
      message.warning(e instanceof Error ? e.message : '链结构不合法')
      return
    }
    setChainSaving(true)
    try {
      await updateConnector(chainConn.id, {
        connName: chainConn.connName, connType: 'CHAIN', spec,
      } as never)
      message.success('编排链已保存')
      setChainConn(null)
      await load(1)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setChainSaving(false)
    }
  }

  const runChainTest = async () => {
    if (!chainConn) return
    let params: Record<string, unknown>
    try {
      params = chainTestParams.trim() ? JSON.parse(chainTestParams) : {}
    } catch {
      message.warning('参数须为合法 JSON')
      return
    }
    setChainTestBusy(true)
    try {
      setChainTestResult(await testConnector(chainConn.id, params))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '试运行失败')
    } finally {
      setChainTestBusy(false)
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
    {
      title: '触发', dataIndex: 'triggerType', width: 90,
      render: (t: string) => t && t !== 'NONE'
        ? <Tag color={t === 'EVENT' ? 'geekblue' : 'purple'}>{t}</Tag> : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '版本', dataIndex: 'currentVersion', width: 70, render: (v: number) => `v${v}` },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '操作', width: 340,
      render: (_: unknown, row: ConnSummary) => (
        <Space>
          {row.connType === 'CHAIN' ? (
            <Button size="small" disabled={!canManage}
              onClick={() => void openChainEditor(row)}>编排</Button>
          ) : (
            row.status !== 'PUBLISHED' && (
              <Button size="small" disabled={!canManage} onClick={() => openEdit(row)}>编辑</Button>
            )
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
          key: 'ai', label: 'AI 模型',
          children: (
            <Card title={<Typography.Text strong>AI 供应商（降级链按优先级升序；ai-gateway 30s 热加载）</Typography.Text>}
              extra={
                <Button type="primary" size="small" icon={<PlusOutlined />} disabled={!canManage}
                  onClick={() => setProvModalOpen(true)}>新建供应商</Button>
              } styles={{ body: { paddingInline: 0 } }}>
              <Table rowKey="id" size="small" dataSource={providers}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                columns={[
                  { title: 'Code', dataIndex: 'providerCode', width: 140 },
                  { title: '名称', dataIndex: 'providerName', width: 160 },
                  { title: 'Base URL', dataIndex: 'baseUrl', ellipsis: true },
                  { title: '模型', dataIndex: 'model', width: 140 },
                  { title: '优先级', dataIndex: 'priority', width: 80 },
                  {
                    title: '状态', dataIndex: 'enabled', width: 90,
                    render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag>,
                  },
                  {
                    title: '操作', width: 190,
                    render: (_: unknown, row: AiProvider) => (
                      <Space>
                        <Button size="small" loading={provTestBusyId === row.id}
                          onClick={() => void testProvider(row)}>测试</Button>
                        <Popconfirm title="停用后 ai-gateway 将跳过该供应商，确定？"
                          onConfirm={() => {
                            void updateAiProvider(row.id, { enabled: row.enabled ? 0 : 1 })
                              .then(loadProviders)
                              .catch((e: unknown) => message.error(e instanceof Error ? e.message : '操作失败'))
                          }} disabled={!canManage}>
                          <Button size="small" disabled={!canManage}>{row.enabled ? '停用' : '启用'}</Button>
                        </Popconfirm>
                        <Popconfirm title="删除该供应商？" onConfirm={() => {
                          void deleteAiProvider(row.id).then(loadProviders)
                            .catch((e: unknown) => message.error(e instanceof Error ? e.message : '删除失败'))
                        }} disabled={!canManage}>
                          <Button size="small" danger icon={<DeleteOutlined />} disabled={!canManage} />
                        </Popconfirm>
                      </Space>
                    ),
                  },
                ]} />
            </Card>
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
        {
          key: 'dlq', label: '死信队列',
          children: (
            <Card title={<Typography.Text strong>触发死信（EVENT/CRON 执行失败；重放原样重投入参）</Typography.Text>}
              extra={
                <Space>
                  <Select style={{ width: 150 }} value={dlqStatus}
                    onChange={(v) => { setDlqStatus(v); void loadDlq(1, v) }}
                    options={[
                      { value: 'PENDING', label: '待重放' },
                      { value: 'RESOLVED', label: '已恢复' },
                      { value: 'DISCARDED', label: '已丢弃' },
                      { value: '', label: '全部' },
                    ]} />
                  <Button icon={<ReloadOutlined />} onClick={() => void loadDlq(dlqPage)} />
                </Space>
              }
              styles={{ body: { paddingInline: 0 } }}>
              <Table rowKey="id" size="small" loading={dlqLoading} dataSource={dlq}
                pagination={{
                  current: dlqPage, total: dlqTotal, pageSize: 15, showSizeChanger: false,
                  onChange: (p) => void loadDlq(p),
                }}
                expandable={{
                  expandedRowRender: (row) => (
                    <div style={{ display: 'grid', gap: 8 }}>
                      <div>
                        <Typography.Text type="secondary">入参（重放原样重投）：</Typography.Text>
                        <pre style={{ margin: '4px 0 0', padding: 8, fontSize: 12, background: token.colorFillQuaternary }}>
                          {row.payloadJson}
                        </pre>
                      </div>
                      {row.errorMsg && (
                        <div>
                          <Typography.Text type="secondary">失败原因：</Typography.Text>
                          <Typography.Text type="danger" style={{ marginLeft: 8 }}>{row.errorMsg}</Typography.Text>
                        </div>
                      )}
                    </div>
                  ),
                }}
                columns={[
                  { title: 'ID', dataIndex: 'id', width: 70 },
                  { title: '连接器', dataIndex: 'connCode', width: 160 },
                  { title: '版本', dataIndex: 'connVersion', width: 70, render: (v: number) => `v${v}` },
                  {
                    title: '触发', dataIndex: 'triggerType', width: 80,
                    render: (t: string) => <Tag color={t === 'EVENT' ? 'geekblue' : 'purple'}>{t}</Tag>,
                  },
                  { title: '来源', dataIndex: 'source', width: 190, ellipsis: true },
                  {
                    title: '状态', dataIndex: 'status', width: 90,
                    render: (s: string) => (
                      <Tag color={s === 'PENDING' ? 'red' : s === 'RESOLVED' ? 'green' : 'default'}>
                        {s === 'PENDING' ? '待重放' : s === 'RESOLVED' ? '已恢复' : '已丢弃'}
                      </Tag>
                    ),
                  },
                  { title: '重试', dataIndex: 'retryCount', width: 60 },
                  { title: '时间', dataIndex: 'createdAt', width: 165 },
                  {
                    title: '操作', width: 170,
                    render: (_: unknown, row: DlqRecord) => (
                      <Space>
                        {row.status === 'PENDING' && (
                          <Button size="small" loading={dlqBusyId === row.id} disabled={!canManage}
                            onClick={() => void replayDlqRow(row)}>重放</Button>
                        )}
                        {row.status === 'PENDING' && (
                          <Popconfirm title="丢弃该死信？保留记录供追溯。" onConfirm={() => void discardDlqRow(row)}
                            disabled={!canManage}>
                            <Button size="small" danger disabled={!canManage}>丢弃</Button>
                          </Popconfirm>
                        )}
                      </Space>
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
        extra={<Button type="primary" loading={saving} disabled={!canManage} onClick={saveNew}>保存</Button>}
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
              <Select style={{ width: 200 }} value={editing ? draft.connType : newType}
                disabled={!!editing}
                onChange={(t) => { setNewType(t); setDraft((d) => ({ ...d, connType: t as ConnType })) }}
                options={CONN_TYPES} />
            </Form.Item>
            {newType === 'CHAIN' && !editing && (
              <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
                保存后将进入编排画布：沿主线添加步骤节点，步骤间以上游输出传参。
              </Typography.Text>
            )}
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
        <Card size="small" title="触发（P2-2：发布后生效；随版本快照不可变）" style={{ marginTop: 16 }}>
          <Space size="large" style={{ display: 'flex' }} wrap>
            <Form.Item label="触发方式" style={{ marginBottom: 0 }}>
              <Select style={{ width: 200 }} value={draft.triggerType}
                onChange={(t) => setDraft((d) => ({ ...d, triggerType: t }))}
                options={TRIGGER_TYPES} />
            </Form.Item>
            {draft.triggerType === 'EVENT' && (
              <>
                <Form.Item label="订阅主题（平台既有）" style={{ marginBottom: 0 }}>
                  <Select style={{ width: 220 }} showSearch value={draft.trigger.topic}
                    onChange={(v) => setDraft((d) => ({
                      ...d, trigger: { ...d.trigger, topic: v, tag: undefined },
                    }))}
                    options={Object.keys(EVENT_TOPICS).map((t) => ({ value: t, label: t }))} />
                </Form.Item>
                <Form.Item label="事件（tag）" style={{ marginBottom: 0 }}>
                  <Select style={{ width: 240 }} showSearch value={draft.trigger.tag}
                    onChange={(v) => setDraft((d) => ({ ...d, trigger: { ...d.trigger, tag: v } }))}
                    options={(draft.trigger.topic ? EVENT_TOPICS[draft.trigger.topic] : [])
                      .map((t) => ({ value: t, label: t }))} />
                </Form.Item>
              </>
            )}
            {draft.triggerType === 'CRON' && (
              <Form.Item label="Cron（Spring 6 段：秒 分 时 日 月 周）" style={{ marginBottom: 0 }}>
                <Input style={{ width: 240, fontFamily: 'monospace' }} placeholder="0 */5 * * * *"
                  value={draft.trigger.cron}
                  onChange={(e) => setDraft((d) => ({ ...d, trigger: { ...d.trigger, cron: e.target.value } }))} />
              </Form.Item>
            )}
          </Space>
          {draft.triggerType !== 'NONE' && (
            <div style={{ marginTop: 12 }}>
              <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                触发入参默认值 JSON（EVENT 触发时事件 payload 覆盖同名键；可用 {'{{param}}'} 渲染进 URL/模板）
              </Typography.Text>
              <Input.TextArea rows={3} value={triggerParamsText} style={{ fontFamily: 'monospace' }}
                onChange={(e) => setTriggerParamsText(e.target.value)} />
            </div>
          )}
        </Card>
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
          ]}
          expandable={{
            rowExpandable: (l) => !!l.stepsJson,
            expandedRowRender: (l) => (
              <pre style={{ margin: 0, padding: 8, fontSize: 12, background: token.colorFillQuaternary }}>
                {l.stepsJson}
              </pre>
            ),
          }} />
      </Modal>

      <Modal
        title={chainConn ? `编排：${chainConn.connName}` : '编排'}
        open={!!chainConn} onCancel={() => setChainConn(null)} width={1100}
        footer={
          <Space>
            <Button onClick={() => setChainConn(null)}>取消</Button>
            <Button icon={<SendOutlined />} onClick={() => { setChainTestOpen(true); setChainTestResult(null) }}>
              整链试运行
            </Button>
            <Button type="primary" loading={chainSaving} disabled={!canManage} onClick={saveChain}>保存</Button>
          </Space>
        }>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          沿主线连线决定执行顺序；点击步骤节点配置连接参数与入参；上游步骤输出经
          {'{{steps.步骤key.body.字段}}'} 引用。保存后重新发布生效。
        </Typography.Text>
        <FlowDesigner value={chainFlow} onChange={setChainFlow}
          nodeTypes={ORCHESTRATION_NODE_TYPES} height={430} />
      </Modal>

      <Modal
        title={`整链试运行：${chainConn?.connName ?? ''}`}
        open={chainTestOpen} onCancel={() => setChainTestOpen(false)} footer={null} width={640}>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          调用入参（JSON 对象；覆盖步骤静态入参）
        </Typography.Text>
        <Input.TextArea rows={4} value={chainTestParams} style={{ fontFamily: 'monospace' }}
          onChange={(e) => setChainTestParams(e.target.value)} />
        <Button type="primary" icon={<SendOutlined />} loading={chainTestBusy}
          onClick={runChainTest} style={{ marginTop: 12 }}>执行</Button>
        {chainTestResult && (
          <>
            <Descriptions size="small" column={2} style={{ marginTop: 16 }} bordered>
              <Descriptions.Item label="状态">
                <Tag color={chainTestResult.status === 'SUCCESS' ? 'green' : 'red'}>
                  {chainTestResult.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="耗时">{chainTestResult.durationMs}ms</Descriptions.Item>
              <Descriptions.Item label="HTTP">{chainTestResult.httpStatus ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="错误">{chainTestResult.error ?? '-'}</Descriptions.Item>
            </Descriptions>
            {chainTestResult.body && (
              <pre style={{
                maxHeight: 220, overflow: 'auto', marginTop: 12, padding: 12, fontSize: 12,
                background: token.colorFillQuaternary,
              }}>{chainTestResult.body}</pre>
            )}
          </>
        )}
      </Modal>

      <Modal
        title="新建 AI 供应商" open={provModalOpen} onCancel={() => setProvModalOpen(false)}
        onOk={saveProvider} confirmLoading={provSaving} destroyOnClose>
        <Form form={provForm} layout="vertical">
          <Form.Item name="providerCode" label="Code"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[a-z][a-z0-9_]{2,63}$/, message: '小写字母开头，仅小写字母/数字/下划线，3~64 位' },
            ]}>
            <Input placeholder="如 glm_main" />
          </Form.Item>
          <Form.Item name="providerName" label="名称" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 智谱主模型" />
          </Form.Item>
          <Form.Item name="baseUrl" label="Base URL（OpenAI 兼容）" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="https://open.bigmodel.cn/api/paas/v4" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item name="apiKey" label="API Key（保存后不可查看，仅可覆盖更新）" rules={[{ required: true, message: '必填' }]}>
            <Input.Password placeholder="sk-..." autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="model" label="默认模型" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 glm-4-flash" />
          </Form.Item>
          <Space size="large" style={{ display: 'flex' }} wrap>
            <Form.Item name="priority" label="降级优先级（越小越先）" initialValue={100} style={{ marginBottom: 0 }}>
              <InputNumber min={1} max={999} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="timeoutMs" label="超时(ms)" initialValue={60000} style={{ marginBottom: 0 }}>
              <InputNumber min={1000} max={120000} step={1000} style={{ width: 130 }} />
            </Form.Item>
          </Space>
        </Form>
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
