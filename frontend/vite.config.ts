import path from 'node:path'
import { defineConfig } from 'vitest/config'
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
  // jsdom, not node: everything worth testing here touches document.cookie, a React tree, or
  // an axios interceptor that reads both. `include` is scoped to src so the built bundle under
  // dist/ is never scanned.
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    restoreMocks: true,
    // vitest's default is 5000ms. StadiumSeatMap renders my-dinh's full 432-slot grid and its
    // tests are the suite's slowest by a wide margin -- 1.5s alone, 1.8s under the full run's
    // parallel load on a quiet developer machine, and 3.5s measured on a busy one. That is a
    // 1.4x margin on a shared CI runner, for a suite whose entire wall time is ten seconds.
    // Nothing here is worth waiting 15s for except a genuine hang.
    testTimeout: 15000,
  },
  build: {
    rollupOptions: {
      output: {
        // Vendor code is pinned in package.json and changes only when a dependency is upgraded;
        // app code changes every deploy. Left to itself the bundler packs both into one shared
        // chunk, so editing a single component invalidates 412 kB of React and framer-motion
        // (131 kB over the wire, now that nginx.conf compresses) in every returning visitor's
        // cache. Naming them separately means a deploy only busts the app chunks. It does not
        // shrink the first load — react-router and framer-motion are both on the landing path
        // via the App shell and Hero; route-level `lazy` (see routes/router.tsx) is what does
        // that.
        // Vite 8 bundles Rolldown, not Rollup: `vite.rollupVersion` still reports 4.23.0 as a
        // compatibility shim, but there is no rollup in the dependency tree. Rolldown takes only
        // the function form — "unlike Rollup, object form is not supported", in its own words
        // — so Rollup's docs are the wrong place to check whether that is still true. Rolldown
        // also marks manualChunks deprecated in favour of output.codeSplitting.
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
