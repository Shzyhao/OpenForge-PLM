import { get, post } from './client'

// ===== 编号规则管理（十轮收口：规则为平台模板 + 计数器水位可见） =====

export interface NumberSegment {
  type: 'CONST' | 'DATE' | 'SEQ'
  value?: string
  pattern?: string
  length?: number
}

export interface NumberRule {
  id: number
  ruleKey: string
  ruleName: string
  segments: string
  resetPolicy: string
  status: string
  tenantId: number
}

export interface NumberCounter {
  ruleKey: string
  period: string
  currentValue: number
}

export function fetchNumberRules(): Promise<NumberRule[]> {
  return get<NumberRule[]>('/api/v1/numbers/rules')
}

export function fetchNumberCounters(): Promise<NumberCounter[]> {
  return get<NumberCounter[]>('/api/v1/numbers/counters')
}

export function createNumberRule(body: {
  ruleKey: string; ruleName: string; segments: NumberSegment[]; resetPolicy: string
}): Promise<NumberRule> {
  return post<NumberRule>('/api/v1/numbers/rules', body)
}

/** 真预览（2026-11-28 审查实锤：原函数走 POST /next 名为预览实为真实取号，实测吃掉真实流水）——不落计数器 */
export function previewNumber(ruleKey: string): Promise<string> {
  return get<string>(`/api/v1/numbers/preview/${ruleKey}`)
}

/** 真实取号（管理面调试用，消费计数器；业务取号走后端服务间调用） */
export function consumeNextNumber(ruleKey: string): Promise<string> {
  return post<string>(`/api/v1/numbers/next/${ruleKey}`)
}

/** 规则停用/启用（2026-11-28 审查补齐：此前只能建不能停） */
export function toggleNumberRule(id: number, enable: boolean): Promise<NumberRule> {
  return post<NumberRule>(`/api/v1/numbers/rules/${id}/${enable ? 'enable' : 'disable'}`)
}
