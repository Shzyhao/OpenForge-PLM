import { get, post, upload } from './client'
import { getToken } from './client'
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

// ===== 文档文件（十轮补齐回读端点：上传后此前永远取不回） =====

export interface DocFile {
  id: number
  docInfoId: number
  fileName: string
  fileSize: number
  sha256: string
  createdAt: string | null
}

export function fetchDocFiles(id: number): Promise<DocFile[]> {
  return get<DocFile[]>(`/api/v1/docs/${id}/files`)
}

/** 上传文档文件（multipart；经 client.upload 统一收口——超时/401 分流/错误体兜底）。 */
export async function uploadDocFile(id: number, file: File): Promise<DocFile> {
  const form = new FormData()
  form.append('file', file)
  return upload<DocFile>(`/api/v1/docs/${id}/files`, form)
}

/** 下载/预览源：blob + 从 Content-Disposition 解析文件名。 */
export async function downloadDocFile(id: number, fileId: number): Promise<{ blob: Blob; fileName: string }> {
  const res = await fetch(`/api/v1/docs/${id}/files/${fileId}/download`, {
    headers: { Authorization: `Bearer ${getToken() ?? ''}` },
  })
  if (!res.ok) {
    throw new Error('下载失败')
  }
  const disposition = res.headers.get('Content-Disposition') ?? ''
  const match = /filename\*=UTF-8''([^;]+)/.exec(disposition)
  const fileName = match ? decodeURIComponent(match[1]) : `doc-${fileId}`
  return { blob: await res.blob(), fileName }
}
