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

// ===== 知识库 / 变更 / 项目域（v1.25 接入）=====

export interface TrashedKnowledge {
  id: number
  title: string
  status: string
}

export interface TrashedChange {
  id: number
  ecrNumber: string
  title: string
  state: string
}

export interface TrashedProject {
  id: number
  projectNumber: string
  name: string
  status: string
}

export function fetchTrashedKnowledge(): Promise<TrashedKnowledge[]> {
  return get<TrashedKnowledge[]>('/api/v1/knowledge/items/recycle')
}

export function restoreKnowledge(id: number): Promise<TrashedKnowledge> {
  return post<TrashedKnowledge>(`/api/v1/knowledge/items/${id}/restore`)
}

export function purgeKnowledge(id: number): Promise<TrashedKnowledge> {
  return post<TrashedKnowledge>(`/api/v1/knowledge/items/${id}/purge`)
}

export function fetchTrashedChanges(): Promise<TrashedChange[]> {
  return get<TrashedChange[]>('/api/v1/changes/requests/recycle')
}

export function restoreChange(id: number): Promise<TrashedChange> {
  return post<TrashedChange>(`/api/v1/changes/requests/${id}/restore`)
}

export function purgeChange(id: number): Promise<TrashedChange> {
  return post<TrashedChange>(`/api/v1/changes/requests/${id}/purge`)
}

export function fetchTrashedProjects(): Promise<TrashedProject[]> {
  return get<TrashedProject[]>('/api/v1/projects/recycle')
}

export function restoreProject(id: number): Promise<TrashedProject> {
  return post<TrashedProject>(`/api/v1/projects/${id}/restore`)
}

export function purgeProject(id: number): Promise<TrashedProject> {
  return post<TrashedProject>(`/api/v1/projects/${id}/purge`)
}
