import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        sidepanel: 'sidepanel.html',
        offscreen: 'offscreen.html',
        'service-worker': 'src/sw/index.ts',
        // Content scripts: keyed with a path-shaped name so rollup writes
        // dist/content/<name>.js (matches manifest.json content_scripts[].js).
        'content/a11y-tree': 'src/content/a11y-tree.ts',
        'content/visual-indicator': 'src/content/visual-indicator.ts',
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunks/[name]-[hash].js',
      },
    },
  },
  test: {
    environment: 'happy-dom',
    globals: true,
  },
})
