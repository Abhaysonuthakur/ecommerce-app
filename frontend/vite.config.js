import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/*
 * The dev proxy exists so the browser only ever talks to one origin.
 *
 * Without it the frontend on :5173 would call the API on :8080 and every request would be
 * cross-origin. That is solvable with CORS, but proxying is the better default here:
 *
 *   - the browser sees same-origin requests, so there is no preflight on every write,
 *     and no chance of a CORS misconfiguration showing up only in production;
 *   - the frontend code uses relative paths ('/api/...'), so the same build works behind
 *     any reverse proxy with no rebuild;
 *   - cookies and the Authorization header behave identically to production.
 *
 * `changeOrigin` rewrites the Host header to the target. Spring Boot does not care, but
 * leaving it off causes confusion with virtual-hosted setups and costs nothing to set.
 */
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
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        /*
         * Three.js is roughly 600 kB on its own and the rest of the app is a fraction of
         * that. Splitting it out means the vendor bundle is cached across deploys instead
         * of being invalidated whenever application code changes - which is what would
         * otherwise happen, because everything lands in one chunk by default.
         */
        manualChunks: {
          three: ['three', '@react-three/fiber', '@react-three/drei'],
          react: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
})
