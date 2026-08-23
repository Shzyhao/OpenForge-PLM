import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发代理：前端 /api → 本地网关(8080)，网关再路由到各业务服务
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
