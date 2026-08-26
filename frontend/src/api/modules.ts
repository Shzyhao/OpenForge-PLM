import { get, post } from './client'

/** 模块注册中心 API（A4 设计 3.5） */

export interface ModuleInfo {
  id: number
  moduleKey: string
  moduleType: 'KERNEL' | 'BUSINESS' | 'AI' | 'EXTENSION'
  displayName: string
  version: string
  status: 'ENABLED' | 'DISABLED' | 'BROKEN'
  routes: string          // JSON 数组字符串
  menu: string | null
  dependencies: string    // JSON 数组字符串
  serviceUri: string | null
  heartbeatAt: string
  registeredAt: string
}

export interface EnabledModule {
  moduleKey: string
  moduleType: string
  displayName: string
  version: string
  menu: string | null
}

export function fetchModules(): Promise<ModuleInfo[]> {
  return get('/api/v1/modules/admin')
}

export function fetchEnabledModules(): Promise<EnabledModule[]> {
  return get('/api/v1/modules')
}

export function disableModule(moduleKey: string): Promise<void> {
  return post(`/api/v1/modules/${moduleKey}/disable`)
}

export function enableModule(moduleKey: string): Promise<void> {
  return post(`/api/v1/modules/${moduleKey}/enable`)
}
