/**
 * 编排链转换（P3 刀3，集成编排器 MVP 设计 §14.3）：后端 spec schemaVersion=2 ↔ 画布 FlowDef。
 * - spec → flow：steps 映射为 STEP 节点（id=step key），补 START/END 端点；执行顺序 = 数组序（主线）；
 *   branches 映射为 from 步骤节点的 rules（条件出边，画布带表达式标注）。
 * - flow → spec：沿 START 出边走唯一路径收集 STEP 节点序为 steps 数组（图形即顺序）；
 *   各步骤 rules 按主线步序 × 规则序导出为 branches（与后端按序求值语义一致）。
 */
import { autoLayout, type FlowDef, type FlowNode, type RuleDef } from './flowModel'

interface StepRaw {
  key: string
  type: string
  spec?: Record<string, unknown>
  params?: Record<string, unknown>
  continueOnError?: boolean
  x?: number
  y?: number
}

interface BranchRaw {
  from: string
  to: string
  expr?: string
}

const START_ID = '__start'
const END_ID = '__end'

/** 后端链 spec（对象形态）→ 画布 FlowDef（含 START/END 端点、主线顺序边与分支条件边）。 */
export function chainToFlow(spec: Record<string, unknown>): FlowDef {
  const steps = Array.isArray(spec.steps) ? (spec.steps as StepRaw[]) : []
  const branches = Array.isArray(spec.branches) ? (spec.branches as BranchRaw[]) : []
  const rulesByFrom = new Map<string, RuleDef[]>()
  for (const b of branches) {
    if (!b || typeof b.from !== 'string' || typeof b.to !== 'string') continue
    const expr = typeof b.expr === 'string' ? b.expr.trim() : ''
    const rules = rulesByFrom.get(b.from) ?? []
    rules.push(expr ? { expr, to: b.to } : { to: b.to })
    rulesByFrom.set(b.from, rules)
  }
  const nodes: FlowNode[] = [{ id: START_ID, type: 'START', x: 40, y: 80 }]
  steps.forEach((s, i) => {
    const rules = rulesByFrom.get(s.key)
    nodes.push({
      id: s.key, type: 'STEP', name: s.key,
      stepType: s.type, stepSpec: s.spec ?? {}, stepParams: s.params ?? {},
      continueOnError: s.continueOnError,
      ...(rules ? { rules } : {}),
      x: s.x ?? 240 + i * 230, y: s.y ?? 80,
    })
  })
  nodes.push({ id: END_ID, type: 'END', x: 240 + steps.length * 230, y: 80 })
  const edges: Array<{ from: string; to: string }> = []
  let prev = START_ID
  for (const s of steps) {
    edges.push({ from: prev, to: s.key })
    prev = s.key
  }
  edges.push({ from: prev, to: END_ID })
  return { nodes, edges }
}

/**
 * 画布 FlowDef → 后端链 spec 对象（schemaVersion=2）。结构非法抛错（message 直接展示）。
 * 分支导出镜像后端 ChainSpecs 约束：分支只指向步骤节点、不指自身、默认分支（表达式留空）每步骤至多一个。
 */
export function flowToChain(def: FlowDef): Record<string, unknown> {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const start = def.nodes.find((n) => n.type === 'START')
  if (!start) throw new Error('链必须有「开始」节点')
  // 沿唯一主线收集 STEP 序
  const order: FlowNode[] = []
  const seen = new Set<string>()
  let cursor: FlowNode | undefined = start
  while (cursor) {
    if (seen.has(cursor.id)) throw new Error('链不允许环形连线')
    seen.add(cursor.id)
    const outEdges = def.edges.filter((e) => e.from === cursor!.id)
    if (cursor.type === 'STEP') order.push(cursor)
    if (cursor.type === 'END') break
    if (outEdges.length !== 1) {
      throw new Error(`「${cursor.name || cursor.id}」需要恰好一条主线出边（条件跳转请用分支连线）`)
    }
    cursor = byId.get(outEdges[0].to)
  }
  for (const n of def.nodes) {
    if (n.type === 'STEP' && !seen.has(n.id)) {
      throw new Error(`步骤「${n.name || n.id}」未连入主线（需从「开始」沿出边可达，否则保存后将丢失）`)
    }
  }
  // 分支导出：主线步序 × 规则序（后端按 branches 数组序逐条求值）
  const branches: BranchRaw[] = []
  const defaultCount = new Map<string, number>()
  for (const n of order) {
    for (const r of n.rules ?? []) {
      const to = byId.get(r.to)
      if (!to || to.type !== 'STEP') {
        throw new Error(`步骤「${n.name || n.id}」的分支只能指向步骤节点`)
      }
      if (to.id === n.id) {
        throw new Error(`步骤「${n.name || n.id}」的分支不能指向自身`)
      }
      const expr = r.expr?.trim() ?? ''
      if (expr) {
        branches.push({ from: n.id, to: r.to, expr })
      } else {
        const c = (defaultCount.get(n.id) ?? 0) + 1
        defaultCount.set(n.id, c)
        if (c > 1) {
          throw new Error(`步骤「${n.name || n.id}」的默认分支（表达式留空）只能有一个`)
        }
        branches.push({ from: n.id, to: r.to })
      }
    }
  }
  const steps = order.map((n) => {
    const step: StepRaw = {
      key: n.id, type: n.stepType ?? 'HTTP_REST',
      spec: (n.stepSpec ?? {}) as Record<string, unknown>,
      params: (n.stepParams ?? {}) as Record<string, unknown>,
    }
    if (n.continueOnError) step.continueOnError = true
    if (n.x !== undefined) step.x = n.x
    if (n.y !== undefined) step.y = n.y
    return step
  })
  if (steps.length === 0) throw new Error('链至少需要一个步骤节点')
  return branches.length
    ? { schemaVersion: 2, steps, branches }
    : { schemaVersion: 2, steps }
}

/** 新建链画布的初始空图（START→END 一条线）。 */
export function emptyChainFlow(): FlowDef {
  return chainToFlow({ steps: [] })
}

/** 既有链编辑回填：无坐标的步骤走自动布局兜底。 */
export function chainFlowWithLayout(spec: Record<string, unknown>): FlowDef {
  const flow = chainToFlow(spec)
  return flow.nodes.some((n) => n.x === undefined) ? autoLayout(flow) : flow
}
