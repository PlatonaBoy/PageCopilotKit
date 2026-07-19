import { defineConfig } from 'vite';
import { resolve } from 'node:path';

export default defineConfig({
  server: {
    port: 5173,
    fs: {
      allow: [resolve(__dirname, '../..')],
    },
  },
  publicDir: 'public',
});
