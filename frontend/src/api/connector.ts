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
  updatedAt: string | null
}

export interface ConnVersionItem {
  version: number
  publishedBy: number | null
  publishedAt: string | null
}

export interface ConnDetail extends ConnSummary {
  spec: Record<string, unknown>
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
}): Promise<ConnDetail> {
  return post('/api/v1/connectors', body)
}

export function updateConnector(id: number, body: {
  connName: string; connType: ConnType; description?: string; spec: Record<string, unknown>
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
