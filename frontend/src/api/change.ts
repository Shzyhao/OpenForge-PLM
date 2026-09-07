import { get, post } from './client'
import type { PageData } from './material'

export interface ChangeRequest {
  id: number
  ecrNumber: string
  title: string
  reason: string | null
  urgency: string
  state: string
  changeType: string
  applyState: string | null
  applyResult: string | null
  workflowInstanceId: number | null
}

export interface EcrDetail extends ChangeRequest {
  payload: string | null
  flowState: string | null
  flowCurrentNode: string | null
}

export function fetchEcrs(params: {
  page?: number
  pageSize?: number
  title?: string
  changeType?: string
}): Promise<PageData<ChangeRequest>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.title) q.set('title', params.title)
  if (params.changeType) q.set('changeType', params.changeType)
  return get<PageData<ChangeRequest>>(`/api/v1/changes/requests?${q.toString()}`)
}

export function createEcr(body: {
  title: string
  reason?: string
  urgency?: string
  changeType?: string
  payload?: string
}): Promise<ChangeRequest> {
  return post<ChangeRequest>('/api/v1/changes/requests', body)
}

export function fetchEcrDetail(id: number): Promise<EcrDetail> {
  return get<EcrDetail>(`/api/v1/changes/requests/${id}`)
}

/** FAILED/PENDING 变更单人工重试执行 */
export function retryApply(id: number): Promise<ChangeRequest> {
  return post<ChangeRequest>(`/api/v1/changes/requests/${id}/apply`)
}

export const URGENCY_LABELS: Record<string, { label: string; color: string }> = {
  LOW: { label: '低', color: 'default' },
  NORMAL: { label: '普通', color: 'blue' },
  HIGH: { label: '紧急', color: 'red' },
}

export const ECR_STATE_LABELS: Record<string, { label: string; color: string }> = {
  SUBMITTED: { label: '评审中', color: 'processing' },
  APPROVED: { label: '已通过', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
}

/** 刀2 类型化变更（统一变更中心） */
export const CHANGE_TYPE_LABELS: Record<string, { label: string; color: string }> = {
  GENERIC: { label: '通用 ECR', color: 'default' },
  SUBSTITUTE_CHANGE: { label: '替代件变更', color: 'orange' },
  PART_STATE_CHANGE: { label: '禁用启用变更', color: 'purple' },
}

/** 审批后执行状态（决策 D6：审批与执行分离） */
export const APPLY_STATE_LABELS: Record<string, { label: string; color: string }> = {
  PENDING: { label: '待执行', color: 'processing' },
  APPLIED: { label: '已生效', color: 'success' },
  FAILED: { label: '执行失败', color: 'error' },
}

export const PART_TARGET_LABELS: Record<string, string> = {
  FROZEN: '禁用',
  RELEASED: '启用',
}

/** 类型化 payload 明细（与后端契约对应） */
export interface SubstitutePayloadItem {
  substitutePartId: number
  partNumber?: string
  name?: string
  priority?: number
  qtyCoefficient?: number
}

export interface SubstitutePayload {
  bomId: number
  bomNumber: string
  version: string
  lineId: number
  position: number
  mainPartNumber: string
  mainPartName: string
  before: SubstitutePayloadItem[]
  after: SubstitutePayloadItem[]
}

export interface WhereUsedRow {
  bomNumber: string
  usageRole: string
  parentPartNumber: string
  parentPartName?: string
  mainPartNumber?: string
  quantity?: number
}

export interface PartStatePayload {
  partId: number
  partNumber: string
  partName: string
  fromState: string
  targetState: string
  whereUsed: WhereUsedRow[]
}
