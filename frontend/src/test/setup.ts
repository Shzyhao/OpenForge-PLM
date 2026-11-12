import { beforeEach } from 'vitest'
import '@testing-library/jest-dom/vitest'

// jsdom 无 fetch 实现，测试内按用例自行 mock global.fetch
// localStorage 清场：client.ts 以 token 为全局状态，用例间不得串扰
beforeEach(() => {
  localStorage.clear()
})

// antd（Grid/Theme）依赖 matchMedia 与 ResizeObserver，jsdom 均未实现
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    }),
  })
}
if (!('ResizeObserver' in window)) {
  ;(window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}
