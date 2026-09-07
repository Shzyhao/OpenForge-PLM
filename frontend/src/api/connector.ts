import { get, post, put, del } from './client'

/** 集成编排器 API（集成编排器 MVP 设计 §6） */

export type ConnType = 'HTTP_REST' | 'JDBC_READONLY'
export type ConnStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

export const CONN_TYPES: { value: ConnType; label: string }[] = [
  { value: 'HTTP_REST', label: 'HTTP/REST 接口' },
  { value: 'JDBC_READONLY', label: '数据库直连（只读）' },
]

export interface ConnSummary {
  id: number
  connCode: string
  connName: string
  connType: ConnType
  status: ConnStatus
  currentVersion: number
  description: string | null
  triggerType: TriggerType
  updatedAt: string | null
}

/** 触发类型（P2-2 §12.2）：NONE=仅手动/API；EVENT=订阅平台事件；CRON=定时 */
export type TriggerType = 'NONE' | 'EVENT' | 'CRON'

export const TRIGGER_TYPES: { value: TriggerType; label: string }[] = [
  { value: 'NONE', label: '无（手动/API）' },
  { value: 'EVENT', label: '事件触发' },
  { value: 'CRON', label: '定时触发' },
]

/** 平台既有事件主题与事件（B2 一域一 topic；EVENT 触发白名单） */
export const EVENT_TOPICS: Record<string, string[]> = {
  'openforge-meta': ['schema.migrated', 'meta.published'],
  'openforge-object': ['object.record.created', 'object.record.updated'],
  'openforge-doc': ['doc.released'],
  'openforge-change': ['change.closed'],
  'openforge-task': ['task.created', 'task.completed'],
  'openforge-connector': ['connector.published'],
}

export interface TriggerForm {
  topic?: string
  tag?: string
  cron?: string
  params?: Record<string, unknown>
}

export interface ConnVersionItem {
  version: number
  publishedBy: number | null
  publishedAt: string | null
}

export interface ConnDetail extends ConnSummary {
  spec: Record<string, unknown>
  trigger: TriggerForm
  versions: ConnVersionItem[]
}

export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** spec 表单形态（与后端 spec JSON 对应，见集成编排器 MVP 设计 §4.1） */
export interface HttpRestSpecForm {
  schemaVersion: number
  method: string
  url: string
  headers: Record<string, string>
  timeoutMs: number
  credentialRef?: string | null
  parameterSchema: { type: string; properties: Record<string, unknown>; required: string[] }
  requestTemplate: Record<string, unknown>
  retry: { maxAttempts: number; backoffMs: number }
}

export interface JdbcSpecForm {
  schemaVersion: number
  datasource: { jdbcUrl: string; username: string }
  passwordRef: string
  allowedTables: string[]
  parameterSchema: { type: string; properties: Record<string, unknown>; required: string[] }
  sqlTemplate: string
  maxRows: number
  timeoutMs: number
}

export interface InvokeResult {
  status: string
  httpStatus: number | null
  rowsReturned: number | null
  body: string | null
  truncated: boolean
  error: string | null
  durationMs: number
}

export interface Credential {
  id: number
  credCode: string
  credName: string
  authType: 'BASIC' | 'BEARER' | 'API_KEY_HEADER' | 'JDBC_PASSWORD'
  extra: string | null
}

export interface ExecLog {
  id: number
  connId: number
  connVersion: number
  triggerType: string
  status: string
  httpStatus: number | null
  rowsReturned: number | null
  durationMs: number
  errorMsg: string | null
  traceId: string | null
  createdAt: string
}

export function fetchConnectors(page = 1, pageSize = 50): Promise<PageData<ConnSummary>> {
  return get(`/api/v1/connectors?page=${page}&pageSize=${pageSize}`)
}

export function fetchConnector(id: number): Promise<ConnDetail> {
  return get(`/api/v1/connectors/${id}`)
}

export function createConnector(body: {
  connCode: string; connName: string; connType: ConnType; description?: string; spec: Record<string, unknown>
  triggerType?: TriggerType; trigger?: TriggerForm
}): Promise<ConnDetail> {
  return post('/api/v1/connectors', body)
}

export function updateConnector(id: number, body: {
  connName: string; connType: ConnType; description?: string; spec: Record<string, unknown>
  triggerType?: TriggerType; trigger?: TriggerForm
}): Promise<ConnDetail> {
  return put(`/api/v1/connectors/${id}`, body)
}

export function deleteConnector(id: number): Promise<void> {
  return del(`/api/v1/connectors/${id}`)
}

export function publishConnector(id: number): Promise<{ connId: number; version: number }> {
  return post(`/api/v1/connectors/${id}/publish`)
}

export function disableConnector(id: number): Promise<{ connId: number; status: string }> {
  return post(`/api/v1/connectors/${id}/disable`)
}

export function testConnector(id: number, params: Record<string, unknown>): Promise<InvokeResult> {
  return post(`/api/v1/connectors/${id}/test`, { params })
}

export function invokeConnector(connCode: string, params: Record<string, unknown>): Promise<InvokeResult> {
  return post(`/api/v1/connectors/invoke/${connCode}`, { params })
}

export function fetchExecLogs(id: number, page = 1, pageSize = 20): Promise<PageData<ExecLog>> {
  return get(`/api/v1/connectors/${id}/exec-logs?page=${page}&pageSize=${pageSize}`)
}

export function fetchCredentials(page = 1, pageSize = 100): Promise<PageData<Credential>> {
  return get(`/api/v1/connector-credentials?page=${page}&pageSize=${pageSize}`)
}

export function createCredential(body: {
  credCode: string; credName: string; authType: string; secret: string; extra?: string
}): Promise<Credential> {
  return post('/api/v1/connector-credentials', body)
}

export function updateCredential(id: number, body: {
  credName?: string; authType?: string; secret?: string; extra?: string
}): Promise<Credential> {
  return put(`/api/v1/connector-credentials/${id}`, body)
}

export function deleteCredential(id: number): Promise<void> {
  return del(`/api/v1/connector-credentials/${id}`)
}

// ===== AI 供应商（P2-1 AI API 配置器，集成编排器 MVP 设计 §12.1） =====

export interface AiProvider {
  id: number
  providerCode: string
  providerName: string
  baseUrl: string
  model: string
  timeoutMs: number
  enabled: boolean
  priority: number
}

export interface ProviderTestResult {
  status: string
  httpStatus: number | null
  durationMs: number
  error: string | null
}

export function fetchAiProviders(page = 1, pageSize = 50): Promise<PageData<AiProvider>> {
  return get(`/api/v1/ai-providers?page=${page}&pageSize=${pageSize}`)
}

export function createAiProvider(body: {
  providerCode: string; providerName: string; baseUrl: string; apiKey: string
  model: string; timeoutMs?: number; enabled?: number; priority?: number
}): Promise<AiProvider> {
  return post('/api/v1/ai-providers', body)
}

export function updateAiProvider(id: number, body: {
  providerName?: string; baseUrl?: string; apiKey?: string; model?: string
  timeoutMs?: number; enabled?: number; priority?: number
}): Promise<AiProvider> {
  return put(`/api/v1/ai-providers/${id}`, body)
}

export function deleteAiProvider(id: number): Promise<void> {
  return del(`/api/v1/ai-providers/${id}`)
}

export function testAiProvider(id: number): Promise<ProviderTestResult> {
  return post(`/api/v1/ai-providers/${id}/test`)
}

// ===== 触发死信（P2-2 §12.2：EVENT/CRON 执行失败落死信，人工重放/丢弃） =====

export interface DlqRecord {
  id: number
  connId: number
  connCode: string
  connVersion: number
  triggerType: string
  source: string | null
  payloadJson: string
  errorMsg: string | null
  retryCount: number
  status: 'PENDING' | 'RESOLVED' | 'DISCARDED'
  createdAt: string
  replayedAt: string | null
}

export function fetchDlq(status?: string, page = 1, pageSize = 20): Promise<PageData<DlqRecord>> {
  const q = status ? `status=${status}&` : ''
  return get(`/api/v1/connectors/dlq?${q}page=${page}&pageSize=${pageSize}`)
}

/** 重放：payload 原样重投；成功 RESOLVED，仍失败 retry_count+1 保持 PENDING */
export function replayDlq(id: number): Promise<{ status: string; retryCount: number }> {
  return post(`/api/v1/connectors/dlq/${id}/replay`)
}

/** 丢弃：保留记录供追溯（status=DISCARDED） */
export function discardDlq(id: number): Promise<void> {
  return del(`/api/v1/connectors/dlq/${id}`)
}
