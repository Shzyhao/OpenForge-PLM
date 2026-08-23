export interface UserInfo {
  id: number
  username: string
  displayName: string
  roles: string[]
}

import { get } from '../api/client'

/** 拉取当前登录用户信息（网关信任头鉴权） */
export async function fetchCurrentUser(): Promise<UserInfo> {
  return get<UserInfo>('/api/v1/users/me')
}
