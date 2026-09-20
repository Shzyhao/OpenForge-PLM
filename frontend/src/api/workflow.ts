import { del, get, post } from './client'

export interface WorkflowDef {
  id: number
  defKey: string
  name: string
  version: number
  status: string
  definition: string
}

export interface WorkflowInstance {
  id: number
  defKey: string
  defVersion: number
  bizType: string
  bizId: number | null
  state: string
  currentNode: string | null
}

export interface WorkflowTask {
  id: number
  instanceId: number
  nodeId: string
  nodeName: string | null
  assigneeId: number | null
  candidateRole: string | null
  action: string | null
  comment: string | null
  /** 委托代办原指派人（v1.23，代办办理的历史任务可见） */
  delegatedFrom?: number | null
  /** 查询期标记：经委托规则进入我的待办（v1.23） */
  viaDelegation?: boolean
}

export function fetchDefs(): Promise<WorkflowDef[]> {
  return get<WorkflowDef[]>('/api/v1/workflow/defs')
}

export function deployDef(body: { defKey: string; name: string; definition: string }): Promise<WorkflowDef> {
  return post<WorkflowDef>('/api/v1/workflow/defs', body)
}

export function fetchMyTasks(): Promise<WorkflowTask[]> {
  return get<WorkflowTask[]>('/api/v1/workflow/tasks/my')
}

export function actTask(taskId: number, action: 'APPROVE' | 'REJECT', comment?: string): Promise<WorkflowInstance> {
  return post<WorkflowInstance>(`/api/v1/workflow/tasks/${taskId}/act`, { action, comment })
}

export function fetchInstance(id: number): Promise<WorkflowInstance> {
  return get<WorkflowInstance>(`/api/v1/workflow/instances/${id}`)
}

// ===== 审批委托（v1.23） =====

export interface WorkflowDelegate {
  id: number
  tenantId: number
  principalId: number
  agentId: number
  defKey: string | null
  startTime: string
  endTime: string | null
  enabled: number
  remark: string | null
  createdAt: string
}

export function fetchDelegates(): Promise<WorkflowDelegate[]> {
  return get<WorkflowDelegate[]>('/api/v1/workflow/delegates')
}

export function createDelegate(body: {
  agentId: number
  defKey?: string
  startTime: string
  endTime?: string
  remark?: string
}): Promise<WorkflowDelegate> {
  return post<WorkflowDelegate>('/api/v1/workflow/delegates', body)
}

export function deleteDelegate(id: number): Promise<void> {
  return del(`/api/v1/workflow/delegates/${id}`)
}

export const INSTANCE_STATE_LABELS: Record<string, { label: string; color: string }> = {
  RUNNING: { label: '进行中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
}
