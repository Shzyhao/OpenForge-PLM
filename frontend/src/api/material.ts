import { get, post } from './client'

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
