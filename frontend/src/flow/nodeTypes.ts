/**
 * 节点类型注册表（P3 画布泛化刀2，集成编排器 MVP 设计 §14.2）：
 * 把 FlowDesigner/flowModel 中的流程域类型分支收敛为"节点行为描述符"——
 * 工作流注册 START/APPROVAL/CONDITION/END（WORKFLOW_NODE_TYPES），
 * 编排画布（刀3）将以同一协议注册 STEP 等类型复用同一画布组件。
 * 本文件为纯模型层（无 React 依赖）；属性面板插槽见 propertyPanels.tsx。
 */
import type { FlowDef, FlowNode, NodeType, RuleDef } from './flowModel'

export interface NodeBehavior {
  type: NodeType
  label: string
  color: string
  /** 扁平小节点（START/END 形态） */
  small?: boolean
  /** 新节点 id 前缀（newNodeId） */
  idPrefix: string
  /** 单例类型：画布上最多一个且用裸前缀作 id（START/END） */
  singleton?: boolean
  /** 是否可作为连线目标 */
  canBeConnectTarget: boolean
  /** 是否有顺序出边（锚点圆点 + edges 通道） */
  hasOutgoingEdge: boolean
  /** 拖出连线时是否经"条件分支表达式" Modal 写入 rules（CONDITION 语义） */
  connectViaRuleModal?: boolean
  /** 可见连线是否含本类型的条件规则展开（rules[].to → rule 视觉边） */
  expandsRules?: boolean
  /** 自动布局是否把 rules[].to 计入分层边 */
  layoutUsesRules?: boolean
  /** 校验是否要求恰好一条顺序出边（START/APPROVAL） */
  requiresExactlyOneOutEdge?: boolean
  /** 节点副标题摘要（画布渲染） */
  summary?: (n: FlowNode) => string
  /** 新增节点默认属性 */
  defaults?: (def: FlowDef) => Partial<FlowNode>
  /** 部署序列化的类型专属输出（APPROVAL assignee / CONDITION rules） */
  serialize?: (n: FlowNode) => Partial<FlowNode>
  /** 类型专属结构校验（APPROVAL 审批人 / CONDITION 分支规则） */
  validate?: (n: FlowNode, ctx: { byId: Map<string, FlowNode> }) => string[]
  /** 画布删除节点时清理引用（CONDITION 的 rules、APPROVAL 的 rejectTo 互指） */
  onNodeRemoved?: (n: FlowNode, removedId: string) => Partial<FlowNode>
}

const APPROVAL: NodeBehavior = {
  type: 'APPROVAL', label: '审批', color: '#1677ff', idPrefix: 'a',
  canBeConnectTarget: true, hasOutgoingEdge: true, requiresExactlyOneOutEdge: true,
  summary: (n) => {
    const a = n.assignee
    if (!a) return '未配置审批人'
    const mode = a.type === 'USERS' ? (n.mode === 'ANY' ? '或签' : '会签') : ''
    if (a.type === 'USER') return `用户 ${a.value ?? '?'}`
    if (a.type === 'ROLE') return `角色 ${a.value ?? '?'}`
    return `多人(${a.values?.length ?? 0})${mode}`
  },
  defaults: () => ({ assignee: { type: 'ROLE', value: '' } }),
  serialize: (n) => {
    if (!n.assignee?.type) return {}
    const a = { type: n.assignee.type } as NonNullable<FlowNode['assignee']>
    if (n.assignee.type === 'USERS') {
      a.values = (n.assignee.values ?? []).map((v) => v.trim()).filter(Boolean)
    } else if (n.assignee.value?.trim()) {
      a.value = n.assignee.value.trim()
    }
    const out: Partial<FlowNode> = { assignee: a }
    if (n.assignee.type === 'USERS' && n.mode === 'ANY') out.mode = 'ANY'
    return out
  },
  validate: (n, ctx) => {
    const label = `「${n.name || '审批'}」`
    const errs: string[] = []
    if (!n.assignee?.type) {
      errs.push(`${label}缺少审批人配置`)
    } else if (n.assignee.type === 'USER' && !n.assignee.value?.trim()) {
      errs.push(`${label}未填写审批用户 id`)
    } else if (n.assignee.type === 'ROLE' && !n.assignee.value?.trim()) {
      errs.push(`${label}未填写角色编码`)
    } else if (n.assignee.type === 'USERS' && (n.assignee.values?.length ?? 0) < 2) {
      errs.push(`${label}多人会签/或签需至少 2 个用户（单人请用 USER/ROLE）`)
    }
    if (n.rejectTo) {
      const target = ctx.byId.get(n.rejectTo)
      if (!target) errs.push(`${label}的驳回回退目标不存在`)
      else if (target.type !== 'APPROVAL') errs.push(`${label}的驳回回退只能指向审批节点`)
    }
    return errs
  },
  onNodeRemoved: (n, removedId) =>
    n.rejectTo === removedId ? { rejectTo: undefined } : {},
}

const CONDITION: NodeBehavior = {
  type: 'CONDITION', label: '条件', color: '#fa8c16', idPrefix: 'c',
  canBeConnectTarget: true, hasOutgoingEdge: false,
  connectViaRuleModal: true, expandsRules: true, layoutUsesRules: true,
  summary: (n) => `${n.rules?.length ?? 0} 分支`,
  defaults: (def) => ({ rules: [{ to: def.nodes.find((x) => x.type === 'END')?.id ?? '' }] }),
  serialize: (n) => ({
    rules: (n.rules ?? []).map((r: RuleDef) => {
      const expr = r.expr?.trim()
      return expr ? { expr, to: r.to } : { to: r.to }
    }),
  }),
  validate: (n, ctx) => {
    const label = `「${n.name || '条件'}」`
    const errs: string[] = []
    const rules = n.rules ?? []
    const defaults = rules.filter((r) => !r.expr?.trim())
    if (rules.length === 0 || defaults.length !== 1) {
      errs.push(`${label}需要分支规则且默认分支（表达式留空）恰好一个`)
    } else if (rules.some((r) => r.expr !== undefined && r.expr !== null && r.expr.trim() === '' && r !== defaults[0])) {
      errs.push(`${label}存在表达式为空白的多余分支`)
    }
    for (const r of rules) {
      if (!ctx.byId.has(r.to)) errs.push(`${label}有分支指向不存在的节点`)
    }
    return errs
  },
  onNodeRemoved: (n, removedId) =>
    n.rules?.some((r) => r.to === removedId)
      ? { rules: n.rules.filter((r) => r.to !== removedId) } : {},
}

const START: NodeBehavior = {
  type: 'START', label: '开始', color: '#52c41a', idPrefix: 'start',
  small: true, singleton: true,
  canBeConnectTarget: false, hasOutgoingEdge: true, requiresExactlyOneOutEdge: true,
}

const END: NodeBehavior = {
  type: 'END', label: '结束', color: '#8c8c8c', idPrefix: 'end',
  small: true, singleton: true,
  canBeConnectTarget: true, hasOutgoingEdge: false,
}

const STEP: NodeBehavior = {
  type: 'STEP', label: '步骤', color: '#722ed1', idPrefix: 's',
  canBeConnectTarget: true, hasOutgoingEdge: true, requiresExactlyOneOutEdge: true,
  summary: (n) => String(n.stepType ?? '未配置类型'),
  defaults: () => ({ stepType: 'HTTP_REST', stepSpec: {}, stepParams: {} }),
}

/** 工作流节点集（注册顺序 = 工具栏展示顺序） */
export const WORKFLOW_NODE_TYPES: NodeType[] = ['START', 'APPROVAL', 'CONDITION', 'END']

/** 编排链节点集（P3 刀3）：START→步骤链→END */
export const ORCHESTRATION_NODE_TYPES: NodeType[] = ['START', 'STEP', 'END']

export const NODE_BEHAVIORS: Record<string, NodeBehavior> = { START, APPROVAL, CONDITION, END, STEP }

export function behaviorOf(type: NodeType): NodeBehavior {
  return NODE_BEHAVIORS[type]
}
