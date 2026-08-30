import { useEffect, useRef, useState } from 'react'
import {
  Button, Divider, Drawer, Input, Modal, Popconfirm, Select, Space, Tag, Typography, message,
} from 'antd'
import {
  ClearOutlined, DeleteOutlined, ExpandOutlined, PartitionOutlined, PlusOutlined,
  ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons'
import {
  NODE_TYPE_META, assigneeSummary, autoLayout, nodeLabel, nodeSize, newNodeId,
  toDefinitionJson, validateFlow, visualEdges,
  type FlowDef, type FlowNode, type NodeType, type RuleDef,
} from '../flow/flowModel'

interface Props {
  value: FlowDef
  onChange: (next: FlowDef) => void
  readOnly?: boolean
  height?: number
}

type View = { tx: number; ty: number; k: number }
type Selection = { kind: 'node'; id: string } | { kind: 'edge'; key: string } | null
type Interaction =
  | { kind: 'drag'; id: string; offX: number; offY: number }
  | { kind: 'connect'; from: string }
  | { kind: 'pan'; sx: number; sy: number; tx: number; ty: number }

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

/** 三次贝塞尔中点（label 锚点） */
function bezierMid(s: { x: number; y: number }, c1: { x: number; y: number },
  c2: { x: number; y: number }, t: { x: number; y: number }) {
  return {
    x: 0.125 * s.x + 0.375 * c1.x + 0.375 * c2.x + 0.125 * t.x,
    y: 0.125 * s.y + 0.375 * c1.y + 0.375 * c2.y + 0.125 * t.y,
  }
}

/**
 * 可视化流程设计器（自研 SVG 画布，零新增依赖）：
 * 拖拽移动 / 右侧圆点拖出连线 / 点选后在面板编辑属性 / 分层自动布局 / 滚轮缩放拖拽平移。
 * 条件节点的出口连线由分支规则渲染（expr 标注），保存时经 toDefinitionJson 规范化。
 */
export default function FlowDesigner({ value, onChange, readOnly = false, height = 520 }: Props) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [view, setView] = useState<View>({ tx: 20, ty: 20, k: 1 })
  const viewRef = useRef(view)
  viewRef.current = view
  const [selected, setSelected] = useState<Selection>(null)
  const [connectPos, setConnectPos] = useState<{ x: number; y: number } | null>(null)
  const [ruleModal, setRuleModal] = useState<{ from: string; to: string; expr: string } | null>(null)
  const interRef = useRef<Interaction | null>(null)

  const nodesById = new Map(value.nodes.map((n) => [n.id, n]))
  const selNode = selected?.kind === 'node' ? nodesById.get(selected.id) ?? null : null
  const selEdge = selected?.kind === 'edge'
    ? visualEdges(value).find((e) => e.key === selected.key) ?? null
    : null

  const toWorld = (clientX: number, clientY: number) => {
    const rect = svgRef.current!.getBoundingClientRect()
    const v = viewRef.current
    return { x: (clientX - rect.left - v.tx) / v.k, y: (clientY - rect.top - v.ty) / v.k }
  }

  // ===== 画布指针交互 =====
  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    const el = e.target as Element
    const handleId = el.getAttribute('data-handle-id')
    const nodeId = el.getAttribute('data-node-id')
    const edgeKey = el.getAttribute('data-edge-key')
    svgRef.current?.setPointerCapture(e.pointerId)
    if (!readOnly && handleId) {
      interRef.current = { kind: 'connect', from: handleId }
      setConnectPos(toWorld(e.clientX, e.clientY))
      return
    }
    if (nodeId) {
      if (readOnly) {
        setSelected({ kind: 'node', id: nodeId })
        return
      }
      const n = nodesById.get(nodeId)
      const w = toWorld(e.clientX, e.clientY)
      if (n && n.x !== undefined && n.y !== undefined) {
        interRef.current = { kind: 'drag', id: nodeId, offX: w.x - n.x, offY: w.y - n.y }
      }
      setSelected({ kind: 'node', id: nodeId })
      return
    }
    if (edgeKey) {
      setSelected({ kind: 'edge', key: edgeKey })
      return
    }
    setSelected(null)
    // 空白处按下：平移画布（只读预览同样支持）
    const v = viewRef.current
    interRef.current = { kind: 'pan', sx: e.clientX, sy: e.clientY, tx: v.tx, ty: v.ty }
  }

  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const it = interRef.current
    if (!it) return
    if (it.kind === 'pan') {
      setView((v) => ({ ...v, tx: it.tx + e.clientX - it.sx, ty: it.ty + e.clientY - it.sy }))
    } else if (it.kind === 'connect') {
      setConnectPos(toWorld(e.clientX, e.clientY))
    } else {
      const w = toWorld(e.clientX, e.clientY)
      onChange({
        ...value,
        nodes: value.nodes.map((n) => (n.id === it.id
          ? { ...n, x: Math.round(w.x - it.offX), y: Math.round(w.y - it.offY) } : n)),
      })
    }
  }

  const onPointerUp = (e: React.PointerEvent<SVGSVGElement>) => {
    const it = interRef.current
    interRef.current = null
    if (it?.kind !== 'connect') return
    setConnectPos(null)
    const w = toWorld(e.clientX, e.clientY)
    const hit = [...value.nodes].reverse().find((n) => {
      const { w: bw, h: bh } = nodeSize(n)
      return n.x !== undefined && n.y !== undefined
        && Math.abs(w.x - n.x) <= bw / 2 && Math.abs(w.y - n.y) <= bh / 2
    })
    if (hit) applyConnect(it.from, hit.id)
  }

  // 滚轮缩放（以光标为中心；React 合成 onWheel 为 passive，需原生监听才能 preventDefault）
  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const rect = svg.getBoundingClientRect()
      const cx = e.clientX - rect.left
      const cy = e.clientY - rect.top
      setView((v) => {
        const k = clamp(v.k * (e.deltaY < 0 ? 1.1 : 0.9), 0.4, 2)
        return { k, tx: cx - ((cx - v.tx) / v.k) * k, ty: cy - ((cy - v.ty) / v.k) * k }
      })
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
  }, [])

  // Delete 键删除选中节点/连线（输入框聚焦时除外）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (readOnly || !selected || (e.key !== 'Delete' && e.key !== 'Backspace')) return
      const tag = (document.activeElement?.tagName ?? '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
      e.preventDefault()
      removeSelected()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, readOnly, value])

  // ===== 模型变更 =====
  const patchNode = (id: string, patch: Partial<FlowNode>) =>
    onChange({ ...value, nodes: value.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) })

  const applyConnect = (fromId: string, toId: string) => {
    if (fromId === toId) return
    const from = nodesById.get(fromId)
    const to = nodesById.get(toId)
    if (!from || !to) return
    if (to.type === 'START') {
      message.warning('「开始」节点不能作为连线的目标')
      return
    }
    if (from.type === 'END') {
      message.warning('「结束」节点没有出边')
      return
    }
    if (from.type === 'CONDITION') {
      setRuleModal({ from: fromId, to: toId, expr: '' })
      return
    }
    onChange({ ...value, edges: [...value.edges.filter((e) => e.from !== fromId), { from: fromId, to: toId }] })
  }

  const confirmRuleModal = () => {
    if (!ruleModal) return
    const from = nodesById.get(ruleModal.from)
    if (!from) return
    const expr = ruleModal.expr.trim()
    if (!expr && (from.rules ?? []).some((r) => !r.expr?.trim() && r.to !== ruleModal.to)) {
      message.warning('该节点已存在默认分支（表达式留空），默认分支只能有一个')
      return
    }
    const rules = (from.rules ?? []).filter((r) => r.to !== ruleModal.to)
    rules.push(expr ? { expr, to: ruleModal.to } : { to: ruleModal.to })
    patchNode(ruleModal.from, { rules })
    setRuleModal(null)
    setSelected({ kind: 'node', id: ruleModal.from })
  }

  const removeSelected = () => {
    if (!selected) return
    if (selected.kind === 'node') {
      onChange({
        nodes: value.nodes.filter((n) => n.id !== selected.id).map((n) => ({
          ...n,
          rules: n.rules?.filter((r) => r.to !== selected.id),
          rejectTo: n.rejectTo === selected.id ? undefined : n.rejectTo,
        })),
        edges: value.edges.filter((e) => e.from !== selected.id && e.to !== selected.id),
      })
    } else {
      const ve = visualEdges(value).find((x) => x.key === selected.key)
      if (ve) {
        if (ve.kind === 'rule') {
          const from = nodesById.get(ve.from)
          const idx = Number(ve.key.split('-')[2])
          if (from?.rules) patchNode(ve.from, { rules: from.rules.filter((_, i) => i !== idx) })
        } else {
          onChange({ ...value, edges: value.edges.filter((e) => !(e.from === ve.from && e.to === ve.to)) })
        }
      }
    }
    setSelected(null)
  }

  const addNode = (type: NodeType) => {
    const svg = svgRef.current
    const rect = svg?.getBoundingClientRect()
    const v = viewRef.current
    const cx = rect ? (rect.width / 2 - v.tx) / v.k : 200
    const cy = rect ? (rect.height / 2 - v.ty) / v.k : 160
    const id = newNodeId(value, type)
    const node: FlowNode = {
      id, type,
      x: Math.round(cx + (Math.random() * 60 - 30)),
      y: Math.round(cy + (Math.random() * 40 - 20)),
    }
    if (type === 'APPROVAL') node.assignee = { type: 'ROLE', value: '' }
    if (type === 'CONDITION') node.rules = [{ to: value.nodes.find((n) => n.type === 'END')?.id ?? '' }]
    onChange({ nodes: [...value.nodes, node], edges: value.edges })
    setSelected({ kind: 'node', id })
  }

  const fitView = () => {
    const svg = svgRef.current
    if (!svg || value.nodes.length === 0) return
    const xs = value.nodes.filter((n) => n.x !== undefined).map((n) => n.x!)
    const ys = value.nodes.filter((n) => n.y !== undefined).map((n) => n.y!)
    if (!xs.length) return
    const minX = Math.min(...xs) - 120
    const maxX = Math.max(...xs) + 120
    const minY = Math.min(...ys) - 80
    const maxY = Math.max(...ys) + 80
    const rect = svg.getBoundingClientRect()
    const k = clamp(Math.min(rect.width / (maxX - minX), rect.height / (maxY - minY), 1.2), 0.4, 1.2)
    setView({ k, tx: (rect.width - (minX + maxX) * k) / 2, ty: (rect.height - (minY + maxY) * k) / 2 })
  }

  // ===== 渲染几何 =====
  const anchor = (n: FlowNode, side: 'right' | 'left') => {
    const { w } = nodeSize(n)
    return { x: (n.x ?? 0) + (side === 'right' ? w / 2 : -w / 2), y: n.y ?? 0 }
  }

  const edgePath = (from: FlowNode, to: FlowNode): { d: string; mid: { x: number; y: number } } => {
    const forward = (to.x ?? 0) > (from.x ?? 0)
    const s = anchor(from, forward ? 'right' : 'left')
    const t = anchor(to, forward ? 'left' : 'right')
    let c1: { x: number; y: number }
    let c2: { x: number; y: number }
    if (forward) {
      const dx = Math.max(50, Math.abs(t.x - s.x) / 2)
      c1 = { x: s.x + dx, y: s.y }
      c2 = { x: t.x - dx, y: t.y }
    } else {
      c1 = { x: s.x - 70, y: s.y + 90 }
      c2 = { x: t.x + 70, y: t.y + 90 }
    }
    return { d: `M ${s.x} ${s.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${t.x} ${t.y}`, mid: bezierMid(s, c1, c2, t) }
  }

  const vEdges = visualEdges(value)
  const connectFrom = interRef.current?.kind === 'connect'
    ? nodesById.get((interRef.current as { from: string }).from) : null

  const errors = validateFlow(value)

  // ===== 属性面板 =====
  const drawerNode = selNode
  const targetOptions = (excludeSelf: boolean) => value.nodes
    .filter((n) => !excludeSelf || n.id !== drawerNode?.id)
    .map((n) => ({ value: n.id, label: `${nodeLabel(n)}（${NODE_TYPE_META[n.type].label}）` }))
  const approvalOptions = value.nodes.filter((n) => n.type === 'APPROVAL' && n.id !== drawerNode?.id)
    .map((n) => ({ value: n.id, label: nodeLabel(n) }))

  return (
    <div>
      {!readOnly && (
        <div style={{ marginBottom: 8, display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center' }}>
          <Space size={4} wrap>
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>新增：</span>
            {(['START', 'APPROVAL', 'CONDITION', 'END'] as NodeType[]).map((t) => (
              <Button key={t} size="small" icon={<PlusOutlined />} onClick={() => addNode(t)}>
                {NODE_TYPE_META[t].label}
              </Button>
            ))}
          </Space>
          <Divider type="vertical" />
          <Space size={4} wrap>
            <Button size="small" icon={<PartitionOutlined />} onClick={() => onChange(autoLayout(value))}>自动布局</Button>
            <Popconfirm title="清空画布？" onConfirm={() => { onChange({ nodes: [], edges: [] }); setSelected(null) }}>
              <Button size="small" icon={<ClearOutlined />} danger>清空</Button>
            </Popconfirm>
          </Space>
          <Divider type="vertical" />
          <Space size={4}>
            <Button size="small" icon={<ZoomOutOutlined />} onClick={() => setView((v) => ({ ...v, k: clamp(v.k * 0.85, 0.4, 2) }))} />
            <Typography.Text type="secondary" style={{ fontSize: 12, width: 42, textAlign: 'center' }}>
              {Math.round(view.k * 100)}%
            </Typography.Text>
            <Button size="small" icon={<ZoomInOutlined />} onClick={() => setView((v) => ({ ...v, k: clamp(v.k * 1.15, 0.4, 2) }))} />
            <Button size="small" icon={<ExpandOutlined />} onClick={fitView} title="适应画布" />
          </Space>
          <Divider type="vertical" />
          <Typography.Text type={errors.length ? 'danger' : 'secondary'} style={{ fontSize: 12 }}>
            {errors.length ? `${errors.length} 项待完善（部署前校验）` : '结构完整 ✓'}
          </Typography.Text>
        </div>
      )}
      <svg
        ref={svgRef}
        data-testid="flow-designer"
        style={{
          width: '100%', height, border: '1px solid #f0f0f0', borderRadius: 8,
          background: '#fafafa', cursor: readOnly ? 'grab' : 'default', display: 'block',
        }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
      >
        <defs>
          <pattern id="of-grid" width="24" height="24" patternUnits="userSpaceOnUse">
            <path d="M 24 0 L 0 0 0 24" fill="none" stroke="#f0f0f0" strokeWidth="1" />
          </pattern>
          {[['#8c8c8c', 'gray'], ['#fa8c16', 'orange'], ['#1677ff', 'blue']].map(([color, id]) => (
            <marker key={id} id={`of-arrow-${id}`} viewBox="0 0 10 10" refX="9" refY="5"
              markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill={color} />
            </marker>
          ))}
        </defs>
        <g transform={`translate(${view.tx} ${view.ty}) scale(${view.k})`}>
          <rect x={-2000} y={-2000} width={6000} height={5000} fill="url(#of-grid)" />
          {vEdges.map((ve) => {
            const from = nodesById.get(ve.from)
            const to = nodesById.get(ve.to)
            if (!from || !to || from.x === undefined || to.x === undefined) return null
            const { d, mid } = edgePath(from, to)
            const active = selected?.kind === 'edge' && selected.key === ve.key
            const color = ve.kind === 'rule' ? '#fa8c16' : '#8c8c8c'
            return (
              <g key={ve.key}>
                <path d={d} data-edge-key={ve.key} fill="none" stroke="transparent" strokeWidth={16}
                  style={{ cursor: 'pointer' }} />
                <path d={d} fill="none" stroke={active ? '#1677ff' : color}
                  strokeWidth={active ? 2.5 : 1.5} markerEnd={`url(#of-arrow-${active ? 'blue' : ve.kind === 'rule' ? 'orange' : 'gray'})`}
                  style={{ pointerEvents: 'none' }} />
                {ve.label && (
                  <g style={{ pointerEvents: 'none' }}>
                    <rect x={mid.x - ve.label.length * 5.6 - 4} y={mid.y - 9}
                      width={ve.label.length * 11.2 + 8} height={18} rx={3}
                      fill="#fff" opacity={0.92} stroke={active ? '#1677ff' : '#fa8c16'} strokeWidth={0.5} />
                    <text x={mid.x} y={mid.y + 4} textAnchor="middle" fontSize={11}
                      fill={ve.label === '默认' ? '#fa8c16' : '#595959'}>{ve.label}</text>
                  </g>
                )}
              </g>
            )
          })}
          {value.nodes.map((n) => {
            const { w, h } = nodeSize(n)
            const x = n.x ?? 0
            const y = n.y ?? 0
            const meta = NODE_TYPE_META[n.type]
            const active = selected?.kind === 'node' && selected.id === n.id
            const small = n.type === 'START' || n.type === 'END'
            return (
              <g key={n.id} data-node-id={n.id} style={{ cursor: readOnly ? 'grab' : 'move' }}>
                {active && <rect x={x - w / 2 - 4} y={y - h / 2 - 4} width={w + 8} height={h + 8}
                  rx={12} fill="none" stroke="#1677ff" strokeWidth={1} strokeDasharray="4 3" />}
                {small
                  ? <rect data-node-id={n.id} x={x - w / 2} y={y - h / 2} width={w} height={h} rx={22}
                    fill="#fff" stroke={meta.color} strokeWidth={active ? 2.5 : 1.5} />
                  : <rect data-node-id={n.id} x={x - w / 2} y={y - h / 2} width={w} height={h} rx={10}
                    fill="#fff" stroke={meta.color} strokeWidth={active ? 2.5 : 1.5} />}
                <text data-node-id={n.id} x={x} y={small ? y + 5 : y - (n.type === 'APPROVAL' ? 6 : 6)}
                  textAnchor="middle" fontSize={small ? 13 : 14} fontWeight={600} fill="#262626"
                  style={{ pointerEvents: 'none' }}>
                  {nodeLabel(n)}
                </text>
                {!small && (
                  <text data-node-id={n.id} x={x} y={y + 14} textAnchor="middle" fontSize={11}
                    fill={n.type === 'APPROVAL' && !n.assignee?.type ? '#cf1322' : '#8c8c8c'}
                    style={{ pointerEvents: 'none' }}>
                    {n.type === 'APPROVAL' ? assigneeSummary(n) : `${n.rules?.length ?? 0} 分支`}
                  </text>
                )}
                {!readOnly && n.type !== 'END' && (
                  <circle data-handle-id={n.id} cx={x + w / 2} cy={y} r={6}
                    fill="#fff" stroke="#1677ff" strokeWidth={1.5} style={{ cursor: 'crosshair' }} />
                )}
              </g>
            )
          })}
          {connectFrom && connectPos && (
            <line
              x1={anchor(connectFrom, 'right').x} y1={connectFrom.y ?? 0}
              x2={connectPos.x} y2={connectPos.y}
              stroke="#1677ff" strokeWidth={1.5} strokeDasharray="6 4"
            />
          )}
        </g>
      </svg>

      <Drawer
        title={selNode ? '节点属性' : '连线'}
        width={360}
        open={!!selected}
        onClose={() => setSelected(null)}
        destroyOnClose
      >
        {selNode && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Space>
              <Tag color={NODE_TYPE_META[selNode.type].color}>{NODE_TYPE_META[selNode.type].label}</Tag>
              <Typography.Text code>{selNode.id}</Typography.Text>
            </Space>
            {(selNode.type === 'APPROVAL' || selNode.type === 'CONDITION') && (
              <label style={{ display: 'block' }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>节点名称</Typography.Text>
                <Input value={selNode.name ?? ''} disabled={readOnly} placeholder={NODE_TYPE_META[selNode.type].label}
                  onChange={(e) => patchNode(selNode.id, { name: e.target.value })} />
              </label>
            )}
            {selNode.type === 'APPROVAL' && (
              <>
                <label style={{ display: 'block' }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>审批人类型</Typography.Text>
                  <Select style={{ width: '100%' }} disabled={readOnly} value={selNode.assignee?.type}
                    onChange={(t) => patchNode(selNode.id, {
                      assignee: t === 'USERS' ? { type: t, values: [] } : { type: t, value: '' },
                      mode: undefined,
                    })}
                    options={[
                      { value: 'USER', label: '指定用户（用户 id）' },
                      { value: 'ROLE', label: '角色（角色编码）' },
                      { value: 'USERS', label: '多用户（会签/或签）' },
                    ]} />
                </label>
                {selNode.assignee?.type === 'USERS' ? (
                  <>
                    <label style={{ display: 'block' }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>用户 id 列表（回车添加，至少 2 人）</Typography.Text>
                      <Select mode="tags" style={{ width: '100%' }} disabled={readOnly} open={false}
                        value={selNode.assignee.values ?? []}
                        onChange={(vs) => patchNode(selNode.id, { assignee: { type: 'USERS', values: vs } })} />
                    </label>
                    <label style={{ display: 'block' }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>审批模式</Typography.Text>
                      <Select style={{ width: '100%' }} disabled={readOnly} value={selNode.mode ?? 'ALL'}
                        onChange={(m) => patchNode(selNode.id, { mode: m })}
                        options={[
                          { value: 'ALL', label: '会签 ALL（全票通过）' },
                          { value: 'ANY', label: '或签 ANY（一人通过）' },
                        ]} />
                    </label>
                  </>
                ) : (
                  <label style={{ display: 'block' }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {selNode.assignee?.type === 'USER' ? '用户 id（数字）' : '角色编码'}
                    </Typography.Text>
                    <Input value={selNode.assignee?.value ?? ''} disabled={readOnly}
                      onChange={(e) => patchNode(selNode.id, {
                        assignee: { type: selNode.assignee!.type, value: e.target.value },
                      })} />
                  </label>
                )}
                <label style={{ display: 'block' }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>驳回回退（REJECT 时）</Typography.Text>
                  <Select style={{ width: '100%' }} disabled={readOnly} allowClear
                    value={selNode.rejectTo || undefined}
                    placeholder="无（驳回终止实例）"
                    onChange={(v) => patchNode(selNode.id, { rejectTo: v ?? undefined })}
                    options={approvalOptions} />
                </label>
              </>
            )}
            {selNode.type === 'CONDITION' && (
              <>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  分支规则（表达式为 SpEL，取自流程变量，如 <code>#amount &gt; 1000</code>；表达式留空的分支即默认分支，需恰好一个）
                </Typography.Text>
                {(selNode.rules ?? []).map((r, i) => {
                  const isDefault = !r.expr?.trim()
                  return (
                    <Space.Compact key={i} style={{ width: '100%' }}>
                      <Input
                        style={{ width: '55%' }} disabled={readOnly}
                        value={r.expr ?? ''}
                        placeholder={isDefault ? '默认分支' : 'SpEL 表达式'}
                        status={!isDefault && !r.expr?.trim() ? 'error' : undefined}
                        onChange={(e) => {
                          const rules = [...(selNode.rules ?? [])]
                          rules[i] = { ...r, expr: e.target.value }
                          patchNode(selNode.id, { rules })
                        }} />
                      <Select style={{ width: '35%' }} disabled={readOnly} value={r.to || undefined}
                        placeholder="目标节点"
                        onChange={(to) => {
                          const rules = [...(selNode.rules ?? [])]
                          rules[i] = { ...r, to }
                          patchNode(selNode.id, { rules })
                        }}
                        options={targetOptions(true)} />
                      <Button style={{ width: '10%' }} disabled={readOnly} danger icon={<DeleteOutlined />}
                        onClick={() => patchNode(selNode.id, {
                          rules: (selNode.rules ?? []).filter((_, j) => j !== i),
                        })} />
                    </Space.Compact>
                  )
                })}
                <Button size="small" disabled={readOnly} icon={<PlusOutlined />}
                  onClick={() => patchNode(selNode.id, {
                    rules: [...(selNode.rules ?? []), { to: value.nodes.find((n) => n.type === 'END')?.id ?? '' } as RuleDef],
                  })}>
                  添加分支
                </Button>
              </>
            )}
            {!readOnly && (
              <Popconfirm title={`删除节点「${nodeLabel(selNode)}」及其关联连线？`} onConfirm={removeSelected}>
                <Button danger icon={<DeleteOutlined />}>删除节点</Button>
              </Popconfirm>
            )}
          </Space>
        )}
        {selEdge && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Typography.Text>
              {nodesById.get(selEdge.from) ? nodeLabel(nodesById.get(selEdge.from)!) : selEdge.from}
              {' → '}
              {nodesById.get(selEdge.to) ? nodeLabel(nodesById.get(selEdge.to)!) : selEdge.to}
            </Typography.Text>
            {selEdge.kind === 'rule' && <Tag color="orange">条件分支：{selEdge.label}</Tag>}
            {!readOnly && <Button danger icon={<DeleteOutlined />} onClick={removeSelected}>删除连线</Button>}
          </Space>
        )}
      </Drawer>

      <Modal
        title="条件分支表达式"
        open={!!ruleModal}
        onOk={confirmRuleModal}
        onCancel={() => setRuleModal(null)}
        okText="确定"
        cancelText="取消"
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          目标：{ruleModal ? nodeLabel(nodesById.get(ruleModal.to) ?? ({ id: ruleModal.to } as FlowNode)) : ''}
          。表达式留空 = 默认分支（兜底）；填写 SpEL 表达式（如 <code>#amount &gt; 1000</code>）。
        </Typography.Paragraph>
        <Input value={ruleModal?.expr ?? ''} autoFocus placeholder="留空作为默认分支，或输入 SpEL"
          onChange={(e) => setRuleModal((m) => (m ? { ...m, expr: e.target.value } : m))} />
      </Modal>
    </div>
  )
}

/** 供页面部署使用的便捷封装：规范化 + 校验一体 */
export function buildDeployDefinition(def: FlowDef): { json?: string; errors: string[] } {
  const errors = validateFlow(def)
  if (errors.length) return { errors }
  return { json: toDefinitionJson(def), errors: [] }
}
