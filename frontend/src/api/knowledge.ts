import { get, post } from './client'
import type { PageData } from './material'

export interface KnowledgeItem {
  id: number
  title: string
  content: string
  summary: string | null
  sourceType: string
  tags: string | null
  qualityScore: number
  usageCount: number
  createdAt: string
}

export interface SearchHit {
  itemId: number
  title: string
  summary: string | null
  score: number
}

export function fetchKnowledge(params: { page?: number; pageSize?: number; keyword?: string }): Promise<PageData<KnowledgeItem>> {
  const q = new URLSearchParams()
  if (params.page) q.set('page', String(params.page))
  if (params.pageSize) q.set('pageSize', String(params.pageSize))
  if (params.keyword) q.set('keyword', params.keyword)
  return get<PageData<KnowledgeItem>>(`/api/v1/knowledge/items?${q.toString()}`)
}

export function createKnowledge(body: { title: string; content: string; tags?: string }): Promise<KnowledgeItem> {
  return post<KnowledgeItem>('/api/v1/knowledge/items', body)
}

export function searchKnowledge(q: string, topK = 5): Promise<SearchHit[]> {
  return get<SearchHit[]>(`/api/v1/knowledge/search?q=${encodeURIComponent(q)}&topK=${topK}`)
}

export function feedbackKnowledge(itemId: number, action: 'CLICK' | 'ADOPT' | 'DISMISS', queryText: string): Promise<void> {
  return post('/api/v1/knowledge/feedback', { itemId, action, queryText })
}
