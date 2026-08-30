/**
 * 流程定义模型与设计器支撑逻辑——与后端 engine.ProcessDefinition 一一对应。
 * 坐标 x/y 仅为设计器布局信息：deploy 原样存储定义 JSON，引擎解析时忽略未知字段
 * （WorkflowEngineIntegrationTest.designedDefinitionWithLayoutCoordinatesDeploysAndRuns 钉住该契约）。
 */

export type NodeType = 'START' | 'APPROVAL' | 'CONDITION' | 'END'

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
}

export interface FlowEdge {
  from: string
  to: string
}

export interface FlowDef {
  nodes: FlowNode[]
  edges: FlowEdge[]
}

export const NODE_TYPE_META: Record<NodeType, { label: string; color: string }> = {
  START: { label: '开始', color: '#52c41a' },
  APPROVAL: { label: '审批', color: '#1677ff' },
  CONDITION: { label: '条件', color: '#fa8c16' },
  END: { label: '结束', color: '#8c8c8c' },
}

/** 画布节点包围盒（世界坐标；START/END 扁，审批/条件大） */
export function nodeSize(n: FlowNode): { w: number; h: number } {
  return n.type === 'START' || n.type === 'END' ? { w: 92, h: 44 } : { w: 176, h: 64 }
}

export function nodeLabel(n: FlowNode): string {
  return n.name || NODE_TYPE_META[n.type].label
}

/** 审批人摘要（节点副标题） */
export function assigneeSummary(n: FlowNode): string {
  const a = n.assignee
  if (!a) return '未配置审批人'
  const mode = a.type === 'USERS' ? (n.mode === 'ANY' ? '或签' : '会签') : ''
  if (a.type === 'USER') return `用户 ${a.value ?? '?'}`
  if (a.type === 'ROLE') return `角色 ${a.value ?? '?'}`
  return `多人(${a.values?.length ?? 0})${mode}`
}

/**
 * 画布可见连线 = edges[]（START/APPROVAL 的顺序流出）+ CONDITION 的规则分支（expr 标注）。
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
    if (n.type === 'CONDITION') {
      n.rules?.forEach((r, i) => out.push({
        key: `r-${n.id}-${i}`, from: n.id, to: r.to,
        label: r.expr?.trim() ? r.expr : '默认', kind: 'rule',
      }))
    }
  }
  return out
}

/**
 * 分层自动布局（左→右）：深度 = 距 START 的最长路径（edges 与条件分支均计入），
 * 不可达节点排到末层。返回带 x/y 的新定义（不改其余字段）。
 */
export function autoLayout(def: FlowDef): FlowDef {
  const depth = new Map<string, number>()
  const start = def.nodes.find((n) => n.type === 'START')
  if (start) depth.set(start.id, 0)
  // 松弛 |V| 次取最长路径（流程图基本是 DAG；环由次数上限兜底）
  const links: Array<[string, string]> = [
    ...def.edges.map((e) => [e.from, e.to] as [string, string]),
    ...def.nodes.filter((n) => n.type === 'CONDITION')
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

/** 新节点 id：类型前缀 + 既有最大序号 + 1 */
export function newNodeId(def: FlowDef, type: NodeType): string {
  const prefix = { START: 'start', APPROVAL: 'a', CONDITION: 'c', END: 'end' }[type]
  let max = 0
  for (const n of def.nodes) {
    const m = new RegExp(`^${prefix}(\\d+)$`).exec(n.id)
    if (m) max = Math.max(max, Number(m[1]))
  }
  if ((type === 'START' || type === 'END') && !def.nodes.some((n) => n.id === prefix)) {
    return prefix
  }
  return `${prefix}${max + 1}`
}

/**
 * 部署用规范化序列化：
 * - 剥离引擎不读的死边（END / CONDITION 的顺序出边——条件出口由 rules[].to 决定）；
 * - 规则表达式空白 → 省略 expr 键（默认分支规范形态，引擎判 expr == null）；
 * - 未填写的可选字段不输出，保持定义 JSON 与引擎规范示例同构。
 */
export function toDefinitionJson(def: FlowDef): string {
  const nodes: FlowNode[] = def.nodes.map((n) => {
    const out: FlowNode = { id: n.id, type: n.type }
    if (n.name?.trim()) out.name = n.name.trim()
    if (n.type === 'APPROVAL' && n.assignee?.type) {
      const a: AssigneeDef = { type: n.assignee.type }
      if (n.assignee.type === 'USERS') {
        a.values = (n.assignee.values ?? []).map((v) => v.trim()).filter(Boolean)
      } else if (n.assignee.value?.trim()) {
        a.value = n.assignee.value.trim()
      }
      out.assignee = a
      if (n.assignee.type === 'USERS' && n.mode === 'ANY') out.mode = 'ANY'
    }
    if (n.type === 'CONDITION') {
      out.rules = (n.rules ?? []).map((r) => {
        const expr = r.expr?.trim()
        return expr ? { expr, to: r.to } : { to: r.to }
      })
    }
    if (n.rejectTo?.trim()) out.rejectTo = n.rejectTo.trim()
    if (n.x !== undefined) out.x = n.x
    if (n.y !== undefined) out.y = n.y
    return out
  })
  const edges = def.edges.filter((e) => {
    const from = def.nodes.find((n) => n.id === e.from)
    return from && from.type !== 'END' && from.type !== 'CONDITION'
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
    if (n.type === 'APPROVAL') {
      if (!n.assignee?.type) {
        errs.push(`${label}缺少审批人配置`)
      } else if (n.assignee.type === 'USER' && !n.assignee.value?.trim()) {
        errs.push(`${label}未填写审批用户 id`)
      } else if (n.assignee.type === 'ROLE' && !n.assignee.value?.trim()) {
        errs.push(`${label}未填写角色编码`)
      } else if (n.assignee.type === 'USERS' && (n.assignee.values?.length ?? 0) < 2) {
        errs.push(`${label}多人会签/或签需至少 2 个用户（单人请用 USER/ROLE）`)
      }
    }
    if (n.type === 'CONDITION') {
      const rules = n.rules ?? []
      const defaults = rules.filter((r) => !r.expr?.trim())
      if (rules.length === 0 || defaults.length !== 1) {
        errs.push(`${label}需要分支规则且默认分支（表达式留空）恰好一个`)
      } else if (rules.some((r) => r.expr !== undefined && r.expr !== null && r.expr.trim() === '' && r !== defaults[0])) {
        errs.push(`${label}存在表达式为空白的多余分支`)
      }
      for (const r of rules) {
        if (!byId.has(r.to)) errs.push(`${label}有分支指向不存在的节点`)
      }
    }
    if (n.type === 'START' || n.type === 'APPROVAL') {
      const out = def.edges.filter((e) => e.from === n.id)
      if (out.length !== 1) errs.push(`${label}需要恰好一条出边（当前 ${out.length} 条）`)
      else if (!byId.has(out[0].to)) errs.push(`${label}的出边指向不存在的节点`)
    }
    if (n.rejectTo) {
      const target = byId.get(n.rejectTo)
      if (!target) errs.push(`${label}的驳回回退目标不存在`)
      else if (target.type !== 'APPROVAL') errs.push(`${label}的驳回回退只能指向审批节点`)
    }
  }
  for (const e of def.edges) {
    const from = byId.get(e.from)
    if (from && (from.type === 'END' || from.type === 'CONDITION')) {
      errs.push(`「${nodeLabel(from)}」不应有顺序出边（条件节点的出口由分支规则决定）`)
    }
    if (!byId.has(e.from) || !byId.has(e.to)) errs.push('存在指向无效节点的连线')
    if (byId.get(e.to)?.type === 'START') errs.push('「开始」节点不能作为连线的目标')
  }
  return errs
}
