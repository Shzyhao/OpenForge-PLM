import { get, post } from './client'

/** 回收站（v1.23）：软删行列表 + 恢复。BOM/文档域暂无删除入口，后续接入时扩展本文件。 */
export interface TrashedPart {
  id: number
  partNumber: string
  name: string
  type: string
  lifecycleState: string
  version: string
  updatedAt: string | null
}

export interface TrashedDrawing {
  id: number
  drawingNumber: string
  title: string
  lifecycleState: string
  updatedAt: string | null
}

export function fetchTrashedParts(): Promise<TrashedPart[]> {
  return get<TrashedPart[]>('/api/v1/parts/recycle')
}

export function restorePart(id: number): Promise<TrashedPart> {
  return post<TrashedPart>(`/api/v1/parts/${id}/restore`)
}

export function fetchTrashedDrawings(): Promise<TrashedDrawing[]> {
  return get<TrashedDrawing[]>('/api/v1/drawings/recycle')
}

export function restoreDrawing(id: number): Promise<TrashedDrawing> {
  return post<TrashedDrawing>(`/api/v1/drawings/${id}/restore`)
}
