import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // Vendor code is pinned in package.json and changes only when a dependency is upgraded;
        // app code changes every deploy. Left to itself Rollup packs both into one shared chunk,
        // so editing a single component invalidates ~200 kB of React and framer-motion in every
        // returning visitor's cache. Naming them separately means a deploy only busts the app
        // chunks. It does not shrink the first load — react-router and framer-motion are both on
        // the landing path via the App shell and Hero; route-level `lazy` (see routes/router.tsx)
        // is what does that.
        // Rollup 4 (Vite 8) takes only the function form here, not the old id-to-modules object.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('framer-motion')) return 'vendor-motion'
          if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(id)) {
            return 'vendor-react'
          }
          return undefined
        },
      },
    },
  },
})
