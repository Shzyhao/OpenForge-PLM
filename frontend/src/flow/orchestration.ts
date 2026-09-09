/**
 * 编排链转换（P3 刀3，集成编排器 MVP 设计 §14.3）：后端 spec schemaVersion=2 ↔ 画布 FlowDef。
 * - spec → flow：steps 映射为 STEP 节点（id=step key），补 START/END 端点；执行顺序 = 数组序；
 * - flow → spec：沿 START 出边走唯一路径收集 STEP 节点序为 steps 数组（图形即顺序）。
 * 分支（多出边）刀4 前不支持——导出时校验报错。
 */
import { autoLayout, type FlowDef, type FlowNode } from './flowModel'

interface StepRaw {
  key: string
  type: string
  spec?: Record<string, unknown>
  params?: Record<string, unknown>
  continueOnError?: boolean
  x?: number
  y?: number
}

const START_ID = '__start'
const END_ID = '__end'

/** 后端链 spec（对象形态）→ 画布 FlowDef（含 START/END 端点与顺序边）。 */
export function chainToFlow(spec: Record<string, unknown>): FlowDef {
  const steps = Array.isArray(spec.steps) ? (spec.steps as StepRaw[]) : []
  const nodes: FlowNode[] = [{ id: START_ID, type: 'START', x: 40, y: 80 }]
  steps.forEach((s, i) => {
    nodes.push({
      id: s.key, type: 'STEP', name: s.key,
      stepType: s.type, stepSpec: s.spec ?? {}, stepParams: s.params ?? {},
      continueOnError: s.continueOnError,
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
 * keepBranches：画布首版不提供分支可视化编辑，既有 branches 原样保留（求值在后端）。
 */
export function flowToChain(def: FlowDef, keepBranches?: unknown[]): Record<string, unknown> {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const start = def.nodes.find((n) => n.type === 'START')
  if (!start) throw new Error('链必须有「开始」节点')
  // 沿唯一路径收集 STEP 序
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
      throw new Error(`「${cursor.name || cursor.id}」需要恰好一条出边（分支将在后续版本支持）`)
    }
    cursor = byId.get(outEdges[0].to)
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
  return keepBranches && keepBranches.length
    ? { schemaVersion: 2, steps, branches: keepBranches }
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
