import { createContext, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ConfigProvider, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'

export type ThemeMode = 'light' | 'dark'
const STORAGE_KEY = 'openforge_theme'

/** 品牌设计 token（README 品牌指南：锻炉橙主色 + 钢铁灰辅色） */
export const BRAND = {
  primary: '#F25C05',
  primaryDark: '#D94E04',
  steel: '#4A5568',
  /** ECharts 统一色板：橙系主 + 钢灰 + 中性扩展 */
  chartPalette: ['#F25C05', '#4A5568', '#F7B27A', '#2C7A7B', '#805AD5', '#3182CE'],
}

interface ThemeModeValue {
  mode: ThemeMode
  toggle: () => void
}

const ThemeModeContext = createContext<ThemeModeValue>({ mode: 'light', toggle: () => undefined })

export function useThemeMode(): ThemeModeValue {
  return useContext(ThemeModeContext)
}

function initialThemeMode(): ThemeMode {
  return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light'
}

export function ThemeModeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(initialThemeMode)

  const value = useMemo<ThemeModeValue>(() => ({
    mode,
    toggle: () => setMode(m => {
      const next: ThemeMode = m === 'dark' ? 'light' : 'dark'
      localStorage.setItem(STORAGE_KEY, next)
      return next
    }),
  }), [mode])

  const themeConfig = useMemo(() => ({
    token: {
      colorPrimary: BRAND.primary,
      colorLink: BRAND.primary,
      borderRadius: 6,
      ...(mode === 'light' ? { colorBgLayout: '#f5f6f8' } : {}),
    },
    algorithm: mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
  }), [mode])

  return (
    <ThemeModeContext.Provider value={value}>
      <ConfigProvider locale={zhCN} theme={themeConfig}>
        {children}
      </ConfigProvider>
    </ThemeModeContext.Provider>
  )
}
