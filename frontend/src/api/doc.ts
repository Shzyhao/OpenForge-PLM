import { get, post } from './client'
import type { PageData } from './material'

export interface DocInfo {
  id: number
  docNumber: string
  title: string
  docType: string
  versionMajor: string
  versionMinor: number
  lifecycleState: string
  checkedOutBy: number | null
}

export function fetchDocs(params: { page?: number; pageSize?: number; title?: string }): Promise<PageData<DocInfo>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.title) q.set('title', params.title)
  return get<PageData<DocInfo>>(`/api/v1/docs?${q.toString()}`)
}

export function createDoc(title: string, docType: string): Promise<DocInfo> {
  return post<DocInfo>('/api/v1/docs', { title, docType })
}

export function checkOut(id: number): Promise<DocInfo> {
  return post<DocInfo>(`/api/v1/docs/${id}/check-out`)
}

export function checkIn(id: number): Promise<DocInfo> {
  return post<DocInfo>(`/api/v1/docs/${id}/check-in`)
}
