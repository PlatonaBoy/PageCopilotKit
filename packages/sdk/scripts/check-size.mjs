#!/usr/bin/env node
// Fails the build when the embeddable bundle grows past its gzip budget.
// The SDK is loaded by every page of the host application, so size is a product requirement.
import { gzipSync } from 'node:zlib';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const bundle = join(here, '..', 'dist', 'enterprise-copilot.js');
const BUDGET_GZIP_KB = 130;

let raw;
try {
  raw = readFileSync(bundle);
} catch {
  console.error(`size check: bundle not found at ${bundle}`);
  process.exit(1);
}

const rawKb = statSync(bundle).size / 1024;
const gzipKb = gzipSync(raw).length / 1024;

console.log(
  `bundle: ${rawKb.toFixed(1)} kB raw, ${gzipKb.toFixed(1)} kB gzip (budget ${BUDGET_GZIP_KB} kB)`,
);

if (gzipKb > BUDGET_GZIP_KB) {
  console.error(
    `size check FAILED: ${gzipKb.toFixed(1)} kB gzip exceeds the ${BUDGET_GZIP_KB} kB budget`,
  );
  process.exit(1);
}
