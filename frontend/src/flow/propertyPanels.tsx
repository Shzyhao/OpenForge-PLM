/**
 * 节点属性面板插槽（P3 画布泛化刀2）：按节点类型注册 React 面板组件，
 * FlowDesigner 的 Drawer 按注册表分派——类型专属表单不再散落在画布组件里。
 * 工作流注册 APPROVAL/CONDITION；编排注册 STEP（刀3，复用 ConnectorConfigPanel）。
 */
import { useEffect, useRef, useState } from 'react'
import { Button, Input, Select, Space, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { NODE_TYPE_META, nodeLabel, type FlowDef, type FlowNode, type RuleDef } from './flowModel'
import ConnectorConfigPanel, { type ConnectorConfigPanelHandle } from '../components/ConnectorConfigPanel'
import { fetchCredentials, type Credential } from '../api/connector'

export interface NodePanelProps {
  node: FlowNode
  def: FlowDef
  readOnly: boolean
  patch: (id: string, patch: Partial<FlowNode>) => void
}

const ApprovalPanel = ({ node, def, readOnly, patch }: NodePanelProps) => {
  const approvalOptions = def.nodes.filter((n) => n.type === 'APPROVAL' && n.id !== node.id)
    .map((n) => ({ value: n.id, label: nodeLabel(n) }))
  return (
    <>
      <label style={{ display: 'block' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>审批人类型</Typography.Text>
        <Select style={{ width: '100%' }} disabled={readOnly} value={node.assignee?.type}
          onChange={(t) => patch(node.id, {
            assignee: t === 'USERS' ? { type: t, values: [] } : { type: t, value: '' },
            mode: undefined,
          })}
          options={[
            { value: 'USER', label: '指定用户（用户 id）' },
            { value: 'ROLE', label: '角色（角色编码）' },
            { value: 'USERS', label: '多用户（会签/或签）' },
          ]} />
      </label>
      {node.assignee?.type === 'USERS' ? (
        <>
          <label style={{ display: 'block' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>用户 id 列表（回车添加，至少 2 人）</Typography.Text>
            <Select mode="tags" style={{ width: '100%' }} disabled={readOnly} open={false}
              value={node.assignee.values ?? []}
              onChange={(vs) => patch(node.id, { assignee: { type: 'USERS', values: vs } })} />
          </label>
          <label style={{ display: 'block' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>审批模式</Typography.Text>
            <Select style={{ width: '100%' }} disabled={readOnly} value={node.mode ?? 'ALL'}
              onChange={(m) => patch(node.id, { mode: m })}
              options={[
                { value: 'ALL', label: '会签 ALL（全票通过）' },
                { value: 'ANY', label: '或签 ANY（一人通过）' },
              ]} />
          </label>
        </>
      ) : (
        <label style={{ display: 'block' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {node.assignee?.type === 'USER' ? '用户 id（数字）' : '角色编码'}
          </Typography.Text>
          <Input value={node.assignee?.value ?? ''} disabled={readOnly}
            onChange={(e) => patch(node.id, {
              assignee: { type: node.assignee!.type, value: e.target.value },
            })} />
        </label>
      )}
      <label style={{ display: 'block' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>驳回回退（REJECT 时）</Typography.Text>
        <Select style={{ width: '100%' }} disabled={readOnly} allowClear
          value={node.rejectTo || undefined}
          placeholder="无（驳回终止实例）"
          onChange={(v) => patch(node.id, { rejectTo: v ?? undefined })}
          options={approvalOptions} />
      </label>
    </>
  )
}

const ConditionPanel = ({ node, def, readOnly, patch }: NodePanelProps) => {
  const targetOptions = def.nodes.filter((n) => n.id !== node.id)
    .map((n) => ({ value: n.id, label: `${nodeLabel(n)}（${NODE_TYPE_META[n.type].label}）` }))
  return (
    <>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        分支规则（表达式为 SpEL，取自流程变量，如 <code>#amount &gt; 1000</code>；表达式留空的分支即默认分支，需恰好一个）
      </Typography.Text>
      {(node.rules ?? []).map((r, i) => {
        const isDefault = !r.expr?.trim()
        return (
          <Space.Compact key={i} style={{ width: '100%' }}>
            <Input
              style={{ width: '55%' }} disabled={readOnly}
              value={r.expr ?? ''}
              placeholder={isDefault ? '默认分支' : 'SpEL 表达式'}
              status={!isDefault && !r.expr?.trim() ? 'error' : undefined}
              onChange={(e) => {
                const rules = [...(node.rules ?? [])]
                rules[i] = { ...r, expr: e.target.value }
                patch(node.id, { rules })
              }} />
            <Select style={{ width: '35%' }} disabled={readOnly} value={r.to || undefined}
              placeholder="目标节点"
              onChange={(to) => {
                const rules = [...(node.rules ?? [])]
                rules[i] = { ...r, to }
                patch(node.id, { rules })
              }}
              options={targetOptions} />
            <Button style={{ width: '10%' }} disabled={readOnly} danger icon={<DeleteOutlined />}
              onClick={() => patch(node.id, {
                rules: (node.rules ?? []).filter((_, j) => j !== i),
              })} />
          </Space.Compact>
        )
      })}
      <Button size="small" disabled={readOnly} icon={<PlusOutlined />}
        onClick={() => patch(node.id, {
          rules: [...(node.rules ?? []), { to: def.nodes.find((n) => n.type === 'END')?.id ?? '' } as RuleDef],
        })}>
        添加分支
      </Button>
    </>
  )
}


// ===== 编排步骤面板（P3 刀3）：类型选择 + ConnectorConfigPanel + 入参默认值 + 失败策略 =====

const StepPanel = ({ node, readOnly, patch }: NodePanelProps) => {
  const [credentials, setCredentials] = useState<Credential[]>([])
  const [applying, setApplying] = useState(false)
  const panelRef = useRef<ConnectorConfigPanelHandle>(null)
  useEffect(() => {
    let alive = true
    fetchCredentials(1, 100).then((r) => {
      if (alive) setCredentials(r.list)
    }).catch(() => undefined)
    return () => { alive = false }
  }, [])
  const stepType = node.stepType ?? 'HTTP_REST'

  const applySpec = async () => {
    if (!panelRef.current) return
    setApplying(true)
    try {
      const spec = await panelRef.current.collect()
      if (spec) patch(node.id, { stepSpec: spec })
    } finally {
      setApplying(false)
    }
  }

  return (
    <>
      <label style={{ display: 'block' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>步骤类型</Typography.Text>
        <Select style={{ width: '100%' }} disabled={readOnly} value={stepType}
          onChange={(t) => patch(node.id, { stepType: t, stepSpec: {} })}
          options={[
            { value: 'HTTP_REST', label: 'HTTP/REST 接口' },
            { value: 'JDBC_READONLY', label: '数据库直连（只读）' },
          ]} />
      </label>
      {Object.keys(node.stepSpec ?? {}).length === 0 && (
        <Typography.Text type="warning" style={{ fontSize: 12 }}>
          尚未配置连接参数——配置后点击「应用到步骤」
        </Typography.Text>
      )}
      <ConnectorConfigPanelLazy
        ref={panelRef} connType={stepType as 'HTTP_REST' | 'JDBC_READONLY'}
        spec={(node.stepSpec ?? {}) as Record<string, unknown>} credentials={credentials} />
      <Button type="primary" size="small" loading={applying} disabled={readOnly} onClick={applySpec}>
        应用到步骤
      </Button>
      <label style={{ display: 'block' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          入参默认值 JSON（调用入参/上游步骤上下文可覆盖；支持 {'{{steps.x.body.k}}'} 引用）
        </Typography.Text>
        <Input.TextArea rows={3} style={{ fontFamily: 'monospace' }} disabled={readOnly}
          value={JSON.stringify(node.stepParams ?? {})}
          onChange={(e) => {
            try { patch(node.id, { stepParams: JSON.parse(e.target.value || '{}') }) } catch { /* 非法 JSON 不落 */ }
          }} />
      </label>
      <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <input type="checkbox" disabled={readOnly} checked={!!node.continueOnError}
          onChange={(e) => patch(node.id, { continueOnError: e.target.checked })} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          本步失败时继续执行后续步骤（整体仍记 FAILED）
        </Typography.Text>
      </label>
    </>
  )
}

// ConnectorConfigPanel 不回依赖 flow，无模块循环；同步插槽直接引用
const ConnectorConfigPanelLazy = ConnectorConfigPanel

/** 面板注册表：key = 节点类型 */
export const NODE_PANEL_SLOTS: Record<string, (props: NodePanelProps) => JSX.Element | null> = {
  APPROVAL: ApprovalPanel,
  CONDITION: ConditionPanel,
  STEP: StepPanel,
}
