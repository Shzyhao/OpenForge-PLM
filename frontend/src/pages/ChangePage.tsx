import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  APPLY_STATE_LABELS, CHANGE_TYPE_LABELS, ECR_STATE_LABELS, PART_TARGET_LABELS, URGENCY_LABELS,
  type ChangeRequest, type EcrDetail, type PartStatePayload, type SubstitutePayload,
  type SubstitutePayloadItem, type WhereUsedRow,
  createEcr, fetchEcrDetail, fetchEcrs, retryApply,
} from '../api/change'
import { INSTANCE_STATE_LABELS } from '../api/workflow'
import {
  PART_STATE_LABELS, fetchBom, fetchBomLines, fetchParts,
  type BomHeader, type BomLineView,
} from '../api/material'

/** 替代组快照表（before/after 共用） */
function SubstituteTable({ rows }: { rows: SubstitutePayloadItem[] }) {
  if (!rows?.length) return <Typography.Text type="secondary">（空）</Typography.Text>
  return (
    <Table rowKey={r => `${r.substitutePartId}-${r.priority}`} size="small" pagination={false}
      dataSource={rows}
      columns={[
        { title: '优先级', dataIndex: 'priority', width: 70 },
        { title: '件号', dataIndex: 'partNumber', width: 140 },
        { title: '名称', dataIndex: 'name' },
        { title: '替代系数', dataIndex: 'qtyCoefficient', width: 90 },
      ] as never} />
  )
}

function WhereUsedTable({ rows }: { rows: WhereUsedRow[] }) {
  if (!rows?.length) return <Typography.Text type="success">无 BOM 引用（可安全禁用）</Typography.Text>
  return (
    <Table rowKey={(r, i) => `${r.bomNumber}-${r.usageRole}-${i}`} size="small" pagination={false}
      dataSource={rows}
      columns={[
        { title: 'BOM', dataIndex: 'bomNumber', width: 170 },
        { title: '用途', dataIndex: 'usageRole', width: 90,
          render: (v: string) => (v === 'SUBSTITUTE' ? '替代用法' : '主件用法') },        { title: '父件', dataIndex: 'parentPartNumber', width: 150 },
        { title: '主件', dataIndex: 'mainPartNumber', render: (v: string | undefined) => v ?? '-' },
      ] as never} />
  )
}

/** 变更中心（刀2 统一变更模块）：通用 ECR + 替代件变更 + 物料禁用启用，审批通过即执行 */
export default function ChangePage() {
  const location = useLocation()
  const navigate = useNavigate()
  const [data, setData] = useState<ChangeRequest[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [typeFilter, setTypeFilter] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [createType, setCreateType] = useState<string>('GENERIC')
  const [form] = Form.useForm()
  // 替代变更创建态：BOM/行选择与快照
  const [loadedBom, setLoadedBom] = useState<BomHeader | null>(null)
  const [loadedLines, setLoadedLines] = useState<BomLineView[]>([])
  const [selectedLine, setSelectedLine] = useState<BomLineView | null>(null)
  const [loadingBom, setLoadingBom] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await fetchEcrs({ page, pageSize: 10, changeType: typeFilter })
      setData(result.list)
      setTotal(result.total)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [page, typeFilter])

  useEffect(() => { load() }, [load])

  /** URL 预填（MaterialPage/BomPage 发起入口跳转）：?action=part-state|substitute */
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const action = params.get('action')
    if (action === 'part-state') {
      openCreate('PART_STATE_CHANGE', {
        partId: params.get('partId') ? Number(params.get('partId')) : undefined,
        targetState: params.get('target') ?? undefined,
      })
    } else if (action === 'substitute') {
      const bomId = params.get('bomId') ? Number(params.get('bomId')) : undefined
      const lineId = params.get('lineId') ? Number(params.get('lineId')) : undefined
      openCreate('SUBSTITUTE_CHANGE', { bomId, lineId })
      if (bomId) void loadBomForCreate(bomId, lineId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.search])

  const loadBomForCreate = async (bomId: number, preferLineId?: number) => {
    setLoadingBom(true)
    try {
      const [bom, lines] = await Promise.all([fetchBom(bomId), fetchBomLines(bomId)])
      setLoadedBom(bom)
      setLoadedLines(lines)
      const line = lines.find(l => l.id === preferLineId) ?? null
      setSelectedLine(line)
      form.setFieldsValue({ lineId: line?.id })
      if (bom.lifecycleState !== 'RELEASED') {
        message.warning('该 BOM 非已发布状态：草稿请直接在 BOM 页编辑替代组')
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'BOM 加载失败')
      setLoadedBom(null)
      setLoadedLines([])
      setSelectedLine(null)
    } finally {
      setLoadingBom(false)
    }
  }

  const openCreate = (type: string, values?: Record<string, unknown>) => {
    setCreateType(type)
    setLoadedBom(null)
    setLoadedLines([])
    setSelectedLine(null)
    form.resetFields()
    if (values) form.setFieldsValue(values)
    setCreateOpen(true)
  }

  const closeCreate = () => {
    setCreateOpen(false)
    navigate('/change', { replace: true }) // 清掉预填参数，避免下次进页重复弹窗
  }

  const submitCreate = async () => {
    const values = await form.validateFields()
    try {
      let payload: string | undefined
      const title = values.title
      if (createType === 'SUBSTITUTE_CHANGE') {
        payload = JSON.stringify({
          bomId: values.bomId,
          lineId: values.lineId,
          after: (values.after ?? []).map((r: { substitutePartId: number; priority?: number; qtyCoefficient?: number }) => ({
            substitutePartId: r.substitutePartId,
            priority: r.priority ?? undefined,
            qtyCoefficient: r.qtyCoefficient ?? undefined,
          })),
        })
      } else if (createType === 'PART_STATE_CHANGE') {
        payload = JSON.stringify({ partId: values.partId, targetState: values.targetState })
      }
      const ecr = await createEcr({
        title,
        reason: values.reason,
        urgency: values.urgency,
        changeType: createType,
        payload,
      })
      message.success(`已提交并进入评审流程：${ecr.ecrNumber}（审批通过后自动执行）`)
      setCreateOpen(false)
      navigate('/change', { replace: true })
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交失败')
    }
  }

  const showDetail = async (id: number) => {
    try {
      const detail: EcrDetail = await fetchEcrDetail(id)
      const flow = detail.flowState
        ? `流程：${INSTANCE_STATE_LABELS[detail.flowState]?.label ?? detail.flowState}${detail.flowCurrentNode ? `（当前节点 ${detail.flowCurrentNode}）` : ''}`
        : '流程状态不可用'

      let payloadBlock: React.ReactNode = null
      if (detail.changeType === 'SUBSTITUTE_CHANGE' && detail.payload) {
        const p = JSON.parse(detail.payload) as SubstitutePayload
        payloadBlock = (
          <>
            <Typography.Text strong>替代组变更 — {p.bomNumber}（{p.version}）行 {p.position}：{p.mainPartNumber} {p.mainPartName}</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Typography.Text type="secondary">变更前</Typography.Text>
              <SubstituteTable rows={p.before ?? []} />
              <Typography.Text type="secondary">变更后</Typography.Text>
              <SubstituteTable rows={p.after ?? []} />
            </div>
          </>
        )
      } else if (detail.changeType === 'PART_STATE_CHANGE' && detail.payload) {
        const p = JSON.parse(detail.payload) as PartStatePayload
        payloadBlock = (
          <>
            <Typography.Text strong>
              物料{PART_TARGET_LABELS[p.targetState] ?? p.targetState} — {p.partNumber} {p.partName}
              （{PART_STATE_LABELS[p.fromState]?.label ?? p.fromState}
               → {PART_STATE_LABELS[p.targetState]?.label ?? p.targetState}）
            </Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Typography.Text type="secondary">禁用前 BOM 引用影响清单（{p.whereUsed?.length ?? 0}）</Typography.Text>
              <WhereUsedTable rows={p.whereUsed ?? []} />
            </div>
          </>
        )
      }

      const applyTag = detail.applyState
        ? <Tag color={APPLY_STATE_LABELS[detail.applyState]?.color}>{APPLY_STATE_LABELS[detail.applyState]?.label ?? detail.applyState}</Tag>
        : null
      Modal.info({
        title: `${detail.ecrNumber} — ${detail.title}`, width: 720,
        content: (
          <div style={{ marginTop: 12 }}>
            <p><Tag color={CHANGE_TYPE_LABELS[detail.changeType]?.color}>{CHANGE_TYPE_LABELS[detail.changeType]?.label ?? detail.changeType}</Tag>
              <Tag color={URGENCY_LABELS[detail.urgency]?.color}>{URGENCY_LABELS[detail.urgency]?.label ?? detail.urgency}</Tag>
              <Tag color={ECR_STATE_LABELS[detail.state]?.color}>{ECR_STATE_LABELS[detail.state]?.label ?? detail.state}</Tag>
              {applyTag}</p>
            <p>{detail.reason ?? '（无变更原因说明）'}</p>
            {payloadBlock}
            {detail.applyResult && (
              <p style={{ marginTop: 8 }}>
                <Typography.Text type={detail.applyState === 'FAILED' ? 'danger' : 'secondary'}>
                  执行结果：{detail.applyResult}
                </Typography.Text>
              </p>
            )}
            <p style={{ color: '#888', fontSize: 12 }}>{flow} · 实例 #{detail.workflowInstanceId}</p>
          </div>
        ),
      })
    } catch (e) {
      message.error(e instanceof Error ? e.message : '详情加载失败')
    }
  }

  const doRetry = async (id: number) => {
    try {
      const ecr = await retryApply(id)
      message.success(`执行完成：${ecr.applyState === 'APPLIED' ? '已生效' : ecr.applyResult ?? '仍失败'}`)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重试失败')
    }
  }

  const columns: ColumnsType<ChangeRequest> = [
    { title: '编号', dataIndex: 'ecrNumber', width: 160 },
    {
      title: '类型', dataIndex: 'changeType', width: 110,
      render: (t: string) => <Tag color={CHANGE_TYPE_LABELS[t]?.color}>{CHANGE_TYPE_LABELS[t]?.label ?? t}</Tag>,
    },
    { title: '标题', dataIndex: 'title', render: (_, r) => <a onClick={() => showDetail(r.id)}>{r.title}</a> },
    { title: '紧急度', dataIndex: 'urgency', width: 80,
      render: (u: string) => <Tag color={URGENCY_LABELS[u]?.color}>{URGENCY_LABELS[u]?.label ?? u}</Tag> },
    { title: '状态', dataIndex: 'state', width: 90,
      render: (s: string) => <Tag color={ECR_STATE_LABELS[s]?.color}>{ECR_STATE_LABELS[s]?.label ?? s}</Tag> },
    {
      title: '执行', key: 'apply', width: 150,
      render: (_, r) => {
        if (r.changeType === 'GENERIC' || !r.applyState) return <Typography.Text type="secondary">-</Typography.Text>
        return (
          <Space size={4}>
            <Tag color={APPLY_STATE_LABELS[r.applyState]?.color}>
              {APPLY_STATE_LABELS[r.applyState]?.label ?? r.applyState}
            </Tag>
            {r.applyState === 'FAILED' && (
              <Button size="small" danger onClick={() => doRetry(r.id)}>重试</Button>
            )}
          </Space>
        )
      },
    },
  ]

  return (
    <Card
      title={<Typography.Text strong>变更中心</Typography.Text>}
      extra={
        <Space>
          <Select
            placeholder="全部类型" allowClear style={{ width: 140 }}
            options={Object.entries(CHANGE_TYPE_LABELS).map(([v, m]) => ({ value: v, label: m.label }))}
            onChange={(v) => { setPage(1); setTypeFilter(v) }}
          />
          <Button icon={<ReloadOutlined />} onClick={load} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate('GENERIC')}>发起变更</Button>
        </Space>
      }
    >
      <Table<ChangeRequest>
        rowKey="id" columns={columns} dataSource={data} loading={loading}
        pagination={{ current: page, total, pageSize: 10, onChange: setPage, showTotal: (t) => `共 ${t} 条` }}
      />

      <Modal
        title={
          <Select value={createType} onChange={setCreateType} style={{ minWidth: 200 }}
            options={Object.entries(CHANGE_TYPE_LABELS).map(([v, m]) => ({ value: v, label: `类型：${m.label}` }))} />
        }
        open={createOpen} destroyOnClose width={640}
        onOk={submitCreate} onCancel={closeCreate} okText="提交评审" cancelText="取消"
      >
        <Form form={form} layout="vertical" initialValues={{ urgency: 'NORMAL' }}>
          <Form.Item name="title" label="变更标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder={createType === 'SUBSTITUTE_CHANGE' ? '如：替代件D 优先级调整'
              : createType === 'PART_STATE_CHANGE' ? '如：停用停产电料 P123'
              : '如：法兰盘材质由45#改为40Cr'} />
          </Form.Item>

          {createType === 'SUBSTITUTE_CHANGE' && (
            <>
              <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
                <Form.Item name="bomId" noStyle rules={[{ required: true, message: '输入 BOM ID' }]}>
                  <InputNumber min={1} placeholder="BOM ID（需已发布）" style={{ width: 200 }} />
                </Form.Item>
                <Button loading={loadingBom}
                  onClick={async () => {
                    const bomId = form.getFieldValue('bomId')
                    if (bomId) await loadBomForCreate(bomId)
                  }}>
                  加载行
                </Button>
              </Space.Compact>
              {loadedBom && (
                <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                  {loadedBom.bomNumber}（{loadedBom.version}，
                  <Tag color={loadedBom.lifecycleState === 'RELEASED' ? 'success' : 'warning'}>
                    {loadedBom.lifecycleState}
                  </Tag>）
                </Typography.Text>
              )}
              <Form.Item name="lineId" label="选择行（主件）" rules={[{ required: true, message: '选择行' }]}>
                <Select placeholder="选择要调整替代组的行" loading={loadingBom}
                  options={loadedLines.map(l => ({ value: l.id, label: `行${l.position} — ${l.childPartNumber} ${l.childPartName}` }))}
                  onChange={(v) => setSelectedLine(loadedLines.find(l => l.id === v) ?? null)} />
              </Form.Item>
              {selectedLine && (
                <div style={{ marginBottom: 12 }}>
                  <Typography.Text type="secondary">当前替代组（提交后由服务端快照存证）</Typography.Text>
                  <SubstituteTable rows={selectedLine.substitutes.map(s => ({
                    substitutePartId: s.substitutePartId, partNumber: s.partNumber,
                    name: s.name, priority: s.priority, qtyCoefficient: s.qtyCoefficient,
                  }))} />
                </div>
              )}
              <Typography.Text strong style={{ display: 'block', marginBottom: 4 }}>变更后替代组</Typography.Text>
              <Form.List name="after" initialValue={[]}>
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(field => (
                      <Space.Compact key={field.key} style={{ width: '100%', marginBottom: 8 }}>
                        <Form.Item name={[field.name, 'substitutePartId']} noStyle
                          rules={[{ required: true, message: '选择替代件' }]}>
                          <PartSelectorForChange placeholder="替代件（已发布）" style={{ width: 260 }} />
                        </Form.Item>
                        <Form.Item name={[field.name, 'priority']} noStyle>
                          <InputNumber min={1} placeholder="优先级" style={{ width: 110 }} />
                        </Form.Item>
                        <Form.Item name={[field.name, 'qtyCoefficient']} noStyle>
                          <InputNumber min={0.0001} step={0.1} placeholder="系数" style={{ width: 110 }} />
                        </Form.Item>
                        <Button danger onClick={() => remove(field.name)}>删</Button>
                      </Space.Compact>
                    ))}
                    <Button type="dashed" block icon={<PlusOutlined />}
                      onClick={() => add({ priority: fields.length + 1 })}>添加替代项</Button>
                  </>
                )}
              </Form.List>
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
                提交后由服务端生成 before 快照；审批通过自动应用到已发布 BOM（可整组清空=删除全部替代件）
              </Typography.Text>
            </>
          )}

          {createType === 'PART_STATE_CHANGE' && (
            <>
              <Form.Item name="partId" label="物料" rules={[{ required: true, message: '选择物料' }]}>
                <PartSelectorForChange placeholder="搜索物料" style={{ width: '100%' }} allStates />
              </Form.Item>
              <Form.Item name="targetState" label="目标状态" rules={[{ required: true, message: '选择目标状态' }]}>
                <Select options={[
                  { value: 'FROZEN', label: '禁用（RELEASED → FROZEN）' },
                  { value: 'RELEASED', label: '启用（FROZEN → RELEASED）' },
                ]} />
              </Form.Item>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                提交时自动生成 BOM 引用影响清单；审批通过即执行状态流转。禁用后该物料不可被新增引用，存量引用不阻断。
              </Typography.Text>
            </>
          )}

          <Form.Item name="reason" label="变更原因" style={{ marginTop: 12 }}>
            <Input.TextArea rows={2} placeholder="说明问题、原因与预期收益" />
          </Form.Item>
          <Form.Item name="urgency" label="紧急程度">
            <Select options={Object.entries(URGENCY_LABELS).map(([v, m]) => ({ value: v, label: m.label }))} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}

/** 物料远程搜索选择（变更表单用）：默认仅已发布，allStates 时不限状态 */
function PartSelectorForChange(props: {
  value?: number
  onChange?: (v: number) => void
  placeholder?: string
  style?: React.CSSProperties
  allStates?: boolean
}) {
  const [options, setOptions] = useState<{ label: string; value: number }[]>([])
  const [loading, setLoading] = useState(false)
  const allStates = props.allStates
  const search = useCallback(async (kw?: string) => {
    setLoading(true)
    try {
      const page = await fetchParts({
        page: 1, pageSize: 20, name: kw || undefined,
        lifecycleState: allStates ? undefined : 'RELEASED',
      })
      setOptions(page.list.map(p => ({
        label: `${p.partNumber} ${p.name}（${PART_STATE_LABELS[p.lifecycleState]?.label ?? p.lifecycleState}）`,
        value: p.id,
      })))
    } finally {
      setLoading(false)
    }
  }, [allStates])
  return (
    <Select
      showSearch allowClear filterOption={false} loading={loading}
      value={props.value} onChange={props.onChange} placeholder={props.placeholder}
      onSearch={kw => search(kw)} onFocus={() => search()} options={options} style={props.style}
    />
  )
}
