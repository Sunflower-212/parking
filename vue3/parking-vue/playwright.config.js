import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  outputDir: '../../.work/playwright-results',
  reporter: [['list'], ['json', { outputFile: '../../.work/playwright-report.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:28088',
    browserName: 'chromium',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})
