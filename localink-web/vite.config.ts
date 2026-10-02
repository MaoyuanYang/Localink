/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
      '/upload': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
  },
  build: {
    rollupOptions: {
      output: {
        // T2 修复 F-15：单 chunk 1.44MB 拆为 vendor 两块——antd 与 react 栈分离，
        // 业务改动只重拉 app chunk，长缓存受益
        manualChunks: {
          'vendor-antd': ['antd'],
          'vendor-react': ['react', 'react-dom', 'react-router-dom', 'zustand', 'axios', 'dayjs'],
        },
      },
    },
  },
})
