import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      // Must NOT be `EnterpriseCopilot` — top-level IIFE `var Name = …` overwrites
      // window.EnterpriseCopilot in browsers and wipes the runtime API (.init).
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
    minify: true,
  },
});
