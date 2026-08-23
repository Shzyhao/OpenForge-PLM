import { get, post } from './client'
import type { PageData } from './material'

export interface ChangeRequest {
  id: number
  ecrNumber: string
  title: string
  reason: string | null
  urgency: string
  state: string
  workflowInstanceId: number | null
}

export interface EcrDetail extends ChangeRequest {
  flowState: string | null
  flowCurrentNode: string | null
}

export function fetchEcrs(params: { page?: number; pageSize?: number; title?: string }): Promise<PageData<ChangeRequest>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.title) q.set('title', params.title)
  return get<PageData<ChangeRequest>>(`/api/v1/changes/requests?${q.toString()}`)
}

export function createEcr(body: { title: string; reason?: string; urgency?: string }): Promise<ChangeRequest> {
  return post<ChangeRequest>('/api/v1/changes/requests', body)
}

export function fetchEcrDetail(id: number): Promise<EcrDetail> {
  return get<EcrDetail>(`/api/v1/changes/requests/${id}`)
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
