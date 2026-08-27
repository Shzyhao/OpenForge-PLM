import { get, post, put, patch, del } from './client'

/** 元对象建模 API（F2 设计 3） */

export type FieldType = 'STRING' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'REFERENCE'

export const FIELD_TYPES: { value: FieldType; label: string }[] = [
  { value: 'STRING', label: '字符串' },
  { value: 'NUMBER', label: '数值' },
  { value: 'DATE', label: '日期' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'REFERENCE', label: '引用' },
]

export interface MetaFieldDef {
  id?: number
  fieldKey: string
  displayName: string
  fieldType: FieldType
  required: boolean
  maxLength?: number | null
  refObject?: string | null
  refField?: string | null
  sortOrder?: number
}

export interface MetaObjectDetail {
  id: number
  objectKey: string
  displayName: string
  tableName: string
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  createdAt: string
  fields: MetaFieldDef[]
}

export interface MetaObjectSummary {
  id: number
  objectKey: string
  displayName: string
  tableName: string
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  fieldCount: number
  createdAt: string
}

export interface MetaPageData<T> {
  total: number
  page: number
  pageSize: number
  items: T[]
}

export interface FieldRequest {
  fieldKey: string
  displayName: string
  fieldType: FieldType
  required?: boolean
  maxLength?: number | null
  refObject?: string | null
  refField?: string | null
}

export function fetchMetaObjects(page = 1, pageSize = 50): Promise<MetaPageData<MetaObjectSummary>> {
  return get(`/api/v1/meta/objects?page=${page}&pageSize=${pageSize}`)
}

export function fetchMetaObject(id: number): Promise<MetaObjectDetail> {
  return get(`/api/v1/meta/objects/${id}`)
}

export function createMetaObject(body: {
  objectKey: string
  displayName: string
  fields: FieldRequest[]
}): Promise<MetaObjectDetail> {
  return post('/api/v1/meta/objects', body)
}

export function updateMetaObject(
  id: number,
  body: { displayName: string; fields: FieldRequest[] },
): Promise<MetaObjectDetail> {
  return put(`/api/v1/meta/objects/${id}`, body)
}

export function publishMetaObject(id: number): Promise<{
  objectId: number
  objectKey: string
  tableName: string
  status: string
  version: number
}> {
  return post(`/api/v1/meta/objects/${id}/publish`)
}

export function previewDdl(id: number): Promise<{ objectId: number; objectKey: string; tableName: string; ddl: string }> {
  return get(`/api/v1/meta/objects/${id}/ddl`)
}

/** 动态记录 CRUD（发布后即刻可用） */

export interface DynamicRecord {
  id: number
  [key: string]: unknown
}

export function fetchRecords(
  objectKey: string,
  params: { page?: number; pageSize?: number; filters?: string[]; sort?: string },
): Promise<MetaPageData<DynamicRecord>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.sort) q.set('sort', params.sort)
  if (params.filters) params.filters.forEach((f) => q.append('filter', f))
  return get(`/api/v1/objects/${objectKey}/records?${q.toString()}`)
}

export function fetchRecord(objectKey: string, id: number): Promise<DynamicRecord> {
  return get(`/api/v1/objects/${objectKey}/records/${id}`)
}

export function createRecord(objectKey: string, body: Record<string, unknown>): Promise<DynamicRecord> {
  return post(`/api/v1/objects/${objectKey}/records`, body)
}

export function updateRecord(
  objectKey: string,
  id: number,
  body: Record<string, unknown>,
): Promise<DynamicRecord> {
  return patch(`/api/v1/objects/${objectKey}/records/${id}`, body)
}

export function deleteRecord(objectKey: string, id: number): Promise<void> {
  return del(`/api/v1/objects/${objectKey}/records/${id}`)
}

/** 表单/列表布局（F3-2 设计器制品） */

export interface LayoutField {
  fieldKey: string
  visible: boolean
  label?: string | null
  width?: number | null
  colSpan?: number | null
}

export interface LayoutData {
  objectId: number
  layoutType: 'FORM' | 'LIST'
  customized: boolean
  fields: LayoutField[]
}

export function fetchLayout(objectId: number, layoutType: 'FORM' | 'LIST'): Promise<LayoutData> {
  return get(`/api/v1/meta/objects/${objectId}/layouts/${layoutType}`)
}

export function saveLayout(
  objectId: number,
  layoutType: 'FORM' | 'LIST',
  fields: LayoutField[],
): Promise<LayoutData> {
  return put(`/api/v1/meta/objects/${objectId}/layouts/${layoutType}`, { fields })
}
