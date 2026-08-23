import { get, post } from './client'
import type { PageData } from './material'

export interface Project {
  id: number
  projectNumber: string
  name: string
  description: string | null
  ownerId: number | null
  status: string
  plannedStart: string | null
  plannedEnd: string | null
}

export interface ProjectTask {
  id: number
  projectId: number
  title: string
  status: string
  assigneeId: number | null
  dueDate: string | null
}

export function fetchProjects(page = 1): Promise<PageData<Project>> {
  return get<PageData<Project>>(`/api/v1/projects?page=${page}&pageSize=10`)
}

export function createProject(body: { name: string; description?: string }): Promise<Project> {
  return post<Project>('/api/v1/projects', body)
}

export function closeProject(id: number): Promise<Project> {
  return post<Project>(`/api/v1/projects/${id}/close`)
}

export function fetchProjectTasks(projectId: number): Promise<ProjectTask[]> {
  return get<ProjectTask[]>(`/api/v1/projects/${projectId}/tasks`)
}

export function addProjectTask(projectId: number, title: string): Promise<ProjectTask> {
  return post<ProjectTask>(`/api/v1/projects/${projectId}/tasks`, { title })
}

export function moveTask(taskId: number, status: string): Promise<ProjectTask> {
  return post<ProjectTask>(`/api/v1/projects/tasks/${taskId}/move`, { status })
}

export function fetchProjectTaskStats(projectId: number): Promise<Record<string, number>> {
  return get<Record<string, number>>(`/api/v1/projects/${projectId}/task-stats`)
}
