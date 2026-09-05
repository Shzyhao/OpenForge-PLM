import { del, get, post, put } from './client'

export interface Part {
  id: number
  partNumber: string
  name: string
  nameEn: string | null
  type: string
  categoryId: number
  attrs: string | null
  unit: string | null
  lifecycleState: string
  version: string
}

export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export interface CategoryNode {
  id: number
  categoryCode: string
  categoryName: string
  parentId: number | null
  sortOrder: number
  children: CategoryNode[]
}

export function fetchParts(params: {
  page?: number
  pageSize?: number
  name?: string
  lifecycleState?: string
}): Promise<PageData<Part>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.name) q.set('name', params.name)
  if (params.lifecycleState) q.set('lifecycleState', params.lifecycleState)
  return get<PageData<Part>>(`/api/v1/parts?${q.toString()}`)
}

export function createPart(body: {
  name: string
  type: string
  categoryId: number
  unit?: string
  attrs?: string
}): Promise<Part> {
  return post<Part>('/api/v1/parts', body)
}

export function partAction(id: number, action: 'submit' | 'approve' | 'reject'): Promise<Part> {
  return post<Part>(`/api/v1/parts/${id}/${action}`)
}

export function fetchCategoryTree(): Promise<CategoryNode[]> {
  return get<CategoryNode[]>('/api/v1/part-categories/tree')
}

export const PART_TYPE_LABELS: Record<string, string> = {
  RAW: '原材料',
  STANDARD: '标准件',
  MADE: '自制件',
  OUTSOURCED: '外购件',
  SEMIFINISHED: '半成品',
  PRODUCT: '成品',
}

export const PART_STATE_LABELS: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  REVIEWING: { label: '评审中', color: 'processing' },
  RELEASED: { label: '已发布', color: 'success' },
  FROZEN: { label: '已冻结', color: 'warning' },
  PHASED_OUT: { label: '已废止', color: 'error' },
}

// ===== BOM（替代件与主数据变更专项 刀1） =====

export interface BomHeader {
  id: number
  bomNumber: string
  parentPartId: number
  bomType: string
  version: string
  lifecycleState: string
}

export interface BomSubstituteView {
  id: number
  substitutePartId: number
  partNumber: string
  name: string
  priority: number
  qtyCoefficient: number
}

export interface BomLineView {
  id: number
  position: number
  childPartId: number
  childPartNumber: string
  childPartName: string
  quantity: number
  refDes: string | null
  usageType: string
  substitutes: BomSubstituteView[]
}

export function fetchBom(id: number): Promise<BomHeader> {
  return get<BomHeader>(`/api/v1/boms/${id}`)
}

export function fetchBomLines(id: number): Promise<BomLineView[]> {
  return get<BomLineView[]>(`/api/v1/boms/${id}/lines`)
}

export function addBomLine(bomId: number, body: {
  childPartId: number
  quantity: number
  refDes?: string
  usageType?: string
}): Promise<unknown> {
  return post(`/api/v1/boms/${bomId}/lines`, body)
}

export function removeBomLine(bomId: number, lineId: number): Promise<void> {
  return del(`/api/v1/boms/${bomId}/lines/${lineId}`)
}

export function addBomSubstitute(bomId: number, lineId: number, body: {
  substitutePartId: number
  priority?: number
  qtyCoefficient?: number
}): Promise<unknown> {
  return post(`/api/v1/boms/${bomId}/lines/${lineId}/substitutes`, body)
}

export function updateBomSubstitute(bomId: number, lineId: number, subId: number, body: {
  priority?: number
  qtyCoefficient?: number
}): Promise<unknown> {
  return put(`/api/v1/boms/${bomId}/lines/${lineId}/substitutes/${subId}`, body)
}

export function removeBomSubstitute(bomId: number, lineId: number, subId: number): Promise<void> {
  return del(`/api/v1/boms/${bomId}/lines/${lineId}/substitutes/${subId}`)
}

export function reviseBom(id: number): Promise<BomHeader> {
  return post<BomHeader>(`/api/v1/boms/${id}/revise`)
}

export const BOM_STATE_LABELS: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  REVIEWING: { label: '评审中', color: 'processing' },
  RELEASED: { label: '已发布', color: 'success' },
}

export const BOM_USAGE_TYPE_LABELS: Record<string, string> = {
  NORMAL: '正常',
  ALTERNATE: '替代',
  OPTIONAL: '选配',
}

export interface BomExpandNode {
  partId: number
  partNumber: string
  name: string
  quantity: number
  substitutes: { partId: number; partNumber: string; name: string; priority: number; qtyCoefficient: number }[]
  children: BomExpandNode[]
}
