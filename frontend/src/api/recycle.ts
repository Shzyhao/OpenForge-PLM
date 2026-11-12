import { get, post } from './client'

/** 回收站（v1.23/v1.24）：软删行列表 + 恢复 + 彻底删除（物料/BOM/文档/图纸四域）。 */
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

/** 彻底删除（v1.24，不可逆）：文件 GC + 行删除。 */
export function purgePart(id: number): Promise<TrashedPart> {
  return post<TrashedPart>(`/api/v1/parts/${id}/purge`)
}

export function purgeDrawing(id: number): Promise<TrashedDrawing> {
  return post<TrashedDrawing>(`/api/v1/drawings/${id}/purge`)
}

// ===== BOM / 文档域（v1.24 接入）=====

export interface TrashedBom {
  id: number
  bomNumber: string
  bomType: string
  version: string
  lifecycleState: string
}

export interface TrashedDoc {
  id: number
  docNumber: string
  title: string
  docType: string
  lifecycleState: string
}

export function fetchTrashedBoms(): Promise<TrashedBom[]> {
  return get<TrashedBom[]>('/api/v1/boms/recycle')
}

export function restoreBom(id: number): Promise<TrashedBom> {
  return post<TrashedBom>(`/api/v1/boms/${id}/restore`)
}

export function purgeBom(id: number): Promise<TrashedBom> {
  return post<TrashedBom>(`/api/v1/boms/${id}/purge`)
}

export function fetchTrashedDocs(): Promise<TrashedDoc[]> {
  return get<TrashedDoc[]>('/api/v1/docs/recycle')
}

export function restoreDoc(id: number): Promise<TrashedDoc> {
  return post<TrashedDoc>(`/api/v1/docs/${id}/restore`)
}

export function purgeDoc(id: number): Promise<TrashedDoc> {
  return post<TrashedDoc>(`/api/v1/docs/${id}/purge`)
}
