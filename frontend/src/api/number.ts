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

export function previewNumber(ruleKey: string): Promise<string> {
  return post<string>(`/api/v1/numbers/next/${ruleKey}`)
}
