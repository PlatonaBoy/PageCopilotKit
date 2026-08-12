import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['github']] : 'list',
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env.DEMO_URL || 'http://127.0.0.1:5173',
        trace: 'retain-on-failure',
        // Allows reusing a Chrome already installed on the machine (e.g. CI images or
        // sandboxes) instead of Playwright's download.
        launchOptions: {
          executablePath: process.env.CHROME_PATH || undefined,
          args: process.env.CHROME_PATH ? ['--no-sandbox', '--disable-gpu'] : [],
        },
      },
    },
  ],
});
