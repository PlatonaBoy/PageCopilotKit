import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import dts from 'vite-plugin-dts';
import { resolve } from 'node:path';

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    // Type declarations are only needed for the published artifact.
    ...(mode === 'test' ? [] : [dts({ include: ['src'], rollupTypes: true, insertTypesEntry: true })]),
  ],
  // Pin React to its production build for the shipped bundle only. Forcing it during tests would
  // strip React.act and break Testing Library.
  define: mode === 'test' ? {} : { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      // Must NOT be `EnterpriseCopilot`: the IIFE wrapper declares `var <name> = ...` at top level,
      // which would overwrite window.EnterpriseCopilot and strip the runtime API.
      name: 'EnterpriseCopilotBundle',
      formats: ['es', 'iife'],
      fileName: (format) =>
        format === 'iife' ? 'enterprise-copilot.js' : 'enterprise-copilot.esm.js',
    },
    rollupOptions: {
      output: {
        exports: 'named',
        inlineDynamicImports: true,
        assetFileNames: 'enterprise-copilot.[ext]',
      },
    },
    cssCodeSplit: false,
    sourcemap: true,
    minify: 'esbuild',
    reportCompressedSize: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    setupFiles: ['./src/test/setup.ts'],
  },
}));
