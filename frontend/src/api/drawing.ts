import { get, post, del } from './client'
import { getToken } from './client'
import type { PageData } from './material'

/** 图纸档案（v1.20.0；对应后端 openforge-drawing DrawingInfo） */
export interface DrawingInfo {
  id: number
  drawingNumber: string
  title: string
  versionMajor: string
  versionMinor: number
  lifecycleState: string
  checkedOutBy: number | null
  createdAt?: string
  updatedAt?: string
}

export interface DrawingFile {
  id: number
  drawingId: number
  fileName: string
  fileSize: number
  sha256: string
  kind: 'MAIN' | 'PREVIEW' | 'ATTACHMENT'
  createdAt?: string
}

export interface DrawingVersion {
  id: number
  drawingId: number
  version: string
  snapshot: string
  state: string
  releasedBy: number | null
  releasedAt?: string
}

export interface DrawingPartLink {
  id: number
  drawingId: number
  partId: number
  partNumber: string
  role: string
}

export interface DrawingByPart {
  drawingId: number
  drawingNumber: string
  title: string
  version: string
  lifecycleState: string
  role: string
}

export function fetchDrawings(params: {
  page?: number
  pageSize?: number
  title?: string
  state?: string
  partId?: number
}): Promise<PageData<DrawingInfo>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.title) q.set('title', params.title)
  if (params.state) q.set('state', params.state)
  if (params.partId) q.set('partId', String(params.partId))
  return get<PageData<DrawingInfo>>(`/api/v1/drawings?${q.toString()}`)
}

export function fetchDrawing(id: number): Promise<DrawingInfo> {
  return get<DrawingInfo>(`/api/v1/drawings/${id}`)
}

export function createDrawing(title: string): Promise<DrawingInfo> {
  return post<DrawingInfo>('/api/v1/drawings', { title })
}

export function deleteDrawing(id: number): Promise<void> {
  return del<void>(`/api/v1/drawings/${id}`)
}

export function fetchDrawingFiles(id: number): Promise<DrawingFile[]> {
  return get<DrawingFile[]>(`/api/v1/drawings/${id}/files`)
}

export function fetchDrawingVersions(id: number): Promise<DrawingVersion[]> {
  return get<DrawingVersion[]>(`/api/v1/drawings/${id}/versions`)
}

export function fetchDrawingParts(id: number): Promise<DrawingPartLink[]> {
  return get<DrawingPartLink[]>(`/api/v1/drawings/${id}/parts`)
}

export function fetchDrawingsByPart(partId: number): Promise<DrawingByPart[]> {
  return get<DrawingByPart[]>(`/api/v1/drawings/by-part/${partId}`)
}

export function linkPart(id: number, partId: number, partNumber: string, role: string): Promise<DrawingPartLink> {
  return post<DrawingPartLink>(`/api/v1/drawings/${id}/parts`, { partId, partNumber, role })
}

export function unlinkPart(id: number, partId: number): Promise<void> {
  return del<void>(`/api/v1/drawings/${id}/parts/${partId}`)
}

export function drawingAction(id: number, action: 'check-out' | 'check-in' | 'submit' | 'approve' | 'reject' | 'obsolete' | 'revise'): Promise<DrawingInfo> {
  return post<DrawingInfo>(`/api/v1/drawings/${id}/${action}`)
}

/** 上传图纸文件（multipart；浏览器自动设置 boundary，不手工指定 Content-Type）。 */
export async function uploadDrawingFile(id: number, file: File, kind: string): Promise<DrawingFile> {
  const form = new FormData()
  form.append('file', file)
  form.append('kind', kind)
  const res = await fetch(`/api/v1/drawings/${id}/files`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${getToken() ?? ''}` },
    body: form,
  })
  const body = await res.json()
  if (!res.ok || body.code !== 0) {
    throw new Error(body.message ?? '上传失败')
  }
  return body.data as DrawingFile
}

/** 下载/预览源：blob + 从 Content-Disposition 解析文件名。 */
export async function downloadDrawingFile(id: number, fileId: number): Promise<{ blob: Blob; fileName: string }> {
  const res = await fetch(`/api/v1/drawings/${id}/files/${fileId}/download`, {
    headers: { Authorization: `Bearer ${getToken() ?? ''}` },
  })
  if (!res.ok) {
    throw new Error('下载失败')
  }
  const disposition = res.headers.get('Content-Disposition') ?? ''
  const match = /filename\*=UTF-8''([^;]+)/.exec(disposition)
  const fileName = match ? decodeURIComponent(match[1]) : `drawing-${fileId}`
  return { blob: await res.blob(), fileName }
}
