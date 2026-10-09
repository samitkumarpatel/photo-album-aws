import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const frontendRoot = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  root: frontendRoot,
  plugins: [react()],
  worker: { format: 'es' },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  build: { outDir: resolve(frontendRoot, '../src/main/resources/static'), emptyOutDir: true },
})
