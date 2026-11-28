import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/// 前端测试基线（v1.24，R14 清单作蓝本逐步补齐）：jsdom + @testing-library/react。
/// 与构建同构（复用 @vitejs/plugin-react），CI Frontend job 随 npm test 执行。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
