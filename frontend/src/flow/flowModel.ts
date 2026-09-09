/**
 * 流程定义模型与设计器支撑逻辑——与后端 engine.ProcessDefinition 一一对应。
 * 坐标 x/y 仅为设计器布局信息：deploy 原样存储定义 JSON，引擎解析时忽略未知字段
 * （WorkflowEngineIntegrationTest.designedDefinitionWithLayoutCoordinatesDeploysAndRuns 钉住该契约）。
 * P3 刀2 泛化：类型相关行为（渲染/连线/序列化/校验）收敛至 nodeTypes.ts 注册表。
 */

import { behaviorOf } from './nodeTypes'

export type NodeType = 'START' | 'APPROVAL' | 'CONDITION' | 'END' | 'STEP'

export interface AssigneeDef {
  type: 'USER' | 'ROLE' | 'USERS'
  value?: string
  values?: string[]
}

/** 条件分支：expr 为 SpEL（如 #amount > 1000）；expr 空/缺省 = 默认分支（兜底，唯一） */
export interface RuleDef {
  expr?: string | null
  to: string
}

export interface FlowNode {
  id: string
  type: NodeType
  name?: string
  assignee?: AssigneeDef
  rules?: RuleDef[]
  mode?: 'ALL' | 'ANY'
  rejectTo?: string | null
  x?: number
  y?: number
  // ===== 编排步骤字段（type=STEP，P3 刀3）=====
  /** 底层连接器类型（HTTP_REST / JDBC_READONLY） */
  stepType?: string
  /** 步骤 spec（v1 单步字段形态） */
  stepSpec?: Record<string, unknown>
  /** 步骤静态入参默认值（被调用入参覆盖） */
  stepParams?: Record<string, unknown>
  /** 失败是否继续执行后续步骤 */
  continueOnError?: boolean
}

export interface FlowEdge {
  from: string
  to: string
}

export interface FlowDef {
  nodes: FlowNode[]
  edges: FlowEdge[]
}

/** 类型元数据（从注册表派生；保留既有导出形状） */
export const NODE_TYPE_META: Record<NodeType, { label: string; color: string }> = {
  START: { label: '开始', color: '#52c41a' },
  APPROVAL: { label: '审批', color: '#1677ff' },
  CONDITION: { label: '条件', color: '#fa8c16' },
  END: { label: '结束', color: '#8c8c8c' },
  STEP: { label: '步骤', color: '#722ed1' },
}

/** 画布节点包围盒（世界坐标；small 类型扁，其余大） */
export function nodeSize(n: FlowNode): { w: number; h: number } {
  return behaviorOf(n.type).small ? { w: 92, h: 44 } : { w: 176, h: 64 }
}

export function nodeLabel(n: FlowNode): string {
  return n.name || behaviorOf(n.type).label
}

/** 审批人摘要（节点副标题；由注册表 summary 承载） */
export function assigneeSummary(n: FlowNode): string {
  return behaviorOf(n.type).summary?.(n) ?? ''
}

/**
 * 画布可见连线 = edges[]（顺序流出）+ 条件类型的规则分支（expr 标注，注册表 expandsRules）。
 * 引擎推进：非条件节点走 edgeFrom 单出边；条件节点完全由 rules[].to 决定（edges 不读）。
 */
export interface VisualEdge {
  key: string
  from: string
  to: string
  label?: string
  kind: 'edge' | 'rule'
}

export function visualEdges(def: FlowDef): VisualEdge[] {
  const out: VisualEdge[] = def.edges.map((e, i) => ({
    key: `e-${i}-${e.from}-${e.to}`, from: e.from, to: e.to, kind: 'edge',
  }))
  for (const n of def.nodes) {
    if (behaviorOf(n.type).expandsRules) {
      n.rules?.forEach((r, i) => out.push({
        key: `r-${n.id}-${i}`, from: n.id, to: r.to,
        label: r.expr?.trim() ? r.expr : '默认', kind: 'rule',
      }))
    }
  }
  return out
}

/**
 * 分层自动布局（左→右）：深度 = 距 START 的最长路径（edges 与注册表 layoutUsesRules
 * 类型的规则分支均计入），不可达节点排到末层。返回带 x/y 的新定义（不改其余字段）。
 */
export function autoLayout(def: FlowDef): FlowDef {
  const depth = new Map<string, number>()
  const start = def.nodes.find((n) => n.type === 'START')
  if (start) depth.set(start.id, 0)
  // 松弛 |V| 次取最长路径（流程图基本是 DAG；环由次数上限兜底）
  const links: Array<[string, string]> = [
    ...def.edges.map((e) => [e.from, e.to] as [string, string]),
    ...def.nodes.filter((n) => behaviorOf(n.type).layoutUsesRules)
      .flatMap((n) => (n.rules ?? []).map((r) => [n.id, r.to] as [string, string])),
  ]
  for (let i = 0; i < def.nodes.length; i++) {
    let changed = false
    for (const [f, t] of links) {
      const df = depth.get(f)
      if (df !== undefined && (depth.get(t) ?? -1) < df + 1) {
        depth.set(t, df + 1)
        changed = true
      }
    }
    if (!changed) break
  }
  const maxDepth = Math.max(0, ...depth.values())
  const layers = new Map<number, FlowNode[]>()
  for (const n of def.nodes) {
    const d = depth.get(n.id) ?? maxDepth + 1
    if (!layers.has(d)) layers.set(d, [])
    layers.get(d)!.push(n)
  }
  const positioned = def.nodes.map((n) => {
    const d = depth.get(n.id) ?? maxDepth + 1
    const idx = layers.get(d)!.indexOf(n)
    return { ...n, x: 60 + d * 250, y: 80 + idx * 130 }
  })
  return { nodes: positioned, edges: def.edges }
}

/** 新节点 id：类型前缀 + 既有最大序号 + 1（单例类型用裸前缀） */
export function newNodeId(def: FlowDef, type: NodeType): string {
  const behavior = behaviorOf(type)
  const prefix = behavior.idPrefix
  let max = 0
  for (const n of def.nodes) {
    const m = new RegExp(`^${prefix}(\\d+)$`).exec(n.id)
    if (m) max = Math.max(max, Number(m[1]))
  }
  if (behavior.singleton && !def.nodes.some((n) => n.id === prefix)) {
    return prefix
  }
  return `${prefix}${max + 1}`
}

/**
 * 部署用规范化序列化：
 * - 剥离引擎不读的死边（无顺序出边通道的类型——条件出口由 rules[].to 决定）；
 * - 规则表达式空白 → 省略 expr 键（默认分支规范形态，引擎判 expr == null）；
 * - 类型专属输出经注册表 serialize；未填写的可选字段不输出。
 */
export function toDefinitionJson(def: FlowDef): string {
  const nodes: FlowNode[] = def.nodes.map((n) => {
    const out: FlowNode = { id: n.id, type: n.type }
    if (n.name?.trim()) out.name = n.name.trim()
    Object.assign(out, behaviorOf(n.type).serialize?.(n) ?? {})
    if (n.rejectTo?.trim()) out.rejectTo = n.rejectTo.trim()
    if (n.x !== undefined) out.x = n.x
    if (n.y !== undefined) out.y = n.y
    return out
  })
  const edges = def.edges.filter((e) => {
    const from = def.nodes.find((n) => n.id === e.from)
    return from && behaviorOf(from.type).hasOutgoingEdge
  })
  return JSON.stringify({ nodes, edges })
}

/** 解析定义 JSON（含宽松容错）：失败返回 null 并带出错误信息 */
export function parseDefinition(text: string): { def: FlowDef | null; error?: string } {
  try {
    const raw = JSON.parse(text) as FlowDef
    if (!Array.isArray(raw.nodes) || !Array.isArray(raw.edges)) {
      return { def: null, error: '定义需要 nodes 与 edges 数组' }
    }
    for (const n of raw.nodes) {
      if (!n.id || !NODE_TYPE_META[n.type]) {
        return { def: null, error: `节点 ${n.id || '(缺 id)'} 的 type 非法: ${String(n.type)}` }
      }
    }
    return { def: { nodes: raw.nodes, edges: raw.edges } }
  } catch (e) {
    return { def: null, error: e instanceof Error ? e.message : 'JSON 解析失败' }
  }
}

/** 客户端校验：镜像 engine.ProcessDefinition.validate() + 连线完备性（非条件节点恰一条出边、默认分支唯一） */
export function validateFlow(def: FlowDef): string[] {
  const errs: string[] = []
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  if (def.nodes.length === 0) return ['画布为空：请先添加节点']
  if (byId.size !== def.nodes.length) errs.push('存在重复的节点 id')

  const starts = def.nodes.filter((n) => n.type === 'START')
  const ends = def.nodes.filter((n) => n.type === 'END')
  if (starts.length !== 1) errs.push('必须有且仅有一个「开始」节点')
  if (ends.length < 1) errs.push('至少需要一个「结束」节点')

  for (const n of def.nodes) {
    const label = `「${nodeLabel(n)}」`
    const behavior = behaviorOf(n.type)
    errs.push(...(behavior.validate?.(n, { byId }) ?? []))
    if (behavior.requiresExactlyOneOutEdge) {
      const out = def.edges.filter((e) => e.from === n.id)
      if (out.length !== 1) errs.push(`${label}需要恰好一条出边（当前 ${out.length} 条）`)
      else if (!byId.has(out[0].to)) errs.push(`${label}的出边指向不存在的节点`)
    }
  }
  for (const e of def.edges) {
    const from = byId.get(e.from)
    if (from && !behaviorOf(from.type).hasOutgoingEdge) {
      errs.push(`「${nodeLabel(from)}」不应有顺序出边（条件节点的出口由分支规则决定）`)
    }
    if (!byId.has(e.from) || !byId.has(e.to)) errs.push('存在指向无效节点的连线')
    if (byId.get(e.to)?.type === 'START') errs.push('「开始」节点不能作为连线的目标')
  }
  return errs
}
