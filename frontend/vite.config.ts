import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发代理：前端 /api → 本地网关(8080)，网关再路由到各业务服务
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // 大依赖独立分包：echarts(~1MB)/antd(~800KB) 变更频率远低于业务代码，
        // 分包后业务迭代不击穿 vendor 缓存，首屏也不再是 2.4MB 单块
        manualChunks: {
          echarts: ['echarts'],
          antd: ['antd', '@ant-design/icons'],
          react: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
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
