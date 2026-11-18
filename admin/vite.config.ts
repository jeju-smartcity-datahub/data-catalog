import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = { '/api': { target: env.SERVER_API_URL || 'http://127.0.0.1:8080', changeOrigin: true } };
  return { base: env.VITE_BASE_PATH || '/', plugins: [react()], server: { proxy }, preview: { proxy } };
});
