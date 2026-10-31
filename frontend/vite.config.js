import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Tauri 前端：标准 Vite + React。
// 沙盒联调：把 /api 代理到 Rust 引擎的 HTTP 服务，模拟 Tauri 的 invoke 调用链。
// 将来在 Tauri 里，/api 会被替换为 tauri.invoke('engine', ...)，前端代码不变。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    host: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9898',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
    },
  },
  clearScreen: false,
});
