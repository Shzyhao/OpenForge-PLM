import { get, post } from './client'

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

export const INSTANCE_STATE_LABELS: Record<string, { label: string; color: string }> = {
  RUNNING: { label: '进行中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
}
