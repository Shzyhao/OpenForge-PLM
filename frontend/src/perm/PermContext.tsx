import { createContext, useContext } from 'react'
import type { UserInfo } from '../api/user'

export interface PermContextValue {
  user: UserInfo | null
  /** 是否有操作权限（SUPER 恒真） */
  hasPerm: (code: string) => boolean
  /** 菜单是否可见 */
  hasMenu: (code: string) => boolean
}

export const PermContext = createContext<PermContextValue>({
  user: null,
  hasPerm: () => false,
  hasMenu: () => false,
})

export function usePerm(): PermContextValue {
  return useContext(PermContext)
}
