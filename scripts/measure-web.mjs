// Read-only browser performance probe. Uses a fresh browser context for each sample.
import { createRequire } from 'node:module'
import { gzipSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'
const require = createRequire(new URL('../vue3/parking-vue/package.json', import.meta.url))
const { chromium } = require('@playwright/test')
const [label = 'optimized', base = 'http://127.0.0.1:28088'] = process.argv.slice(2)
mkdirSync('.work/ui', { recursive: true })
if (!/^https?:\/\/(localhost|127\.0\.0\.1):\d+$/.test(base)) throw new Error('Only local benchmark URLs are supported')
const browser = await chromium.launch({ headless: true })
const browserVersion = browser.version()
const samples = []
try {
  for (let round = 0; round < 3; round++) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await context.newPage()
    await page.addInitScript(() => {
      window.__parkingPerf = { lcp: 0, cls: 0 }
      new PerformanceObserver(list => {
        for (const entry of list.getEntries()) window.__parkingPerf.lcp = entry.startTime
      }).observe({ type: 'largest-contentful-paint', buffered: true })
      new PerformanceObserver(list => {
        for (const entry of list.getEntries()) if (!entry.hadRecentInput) window.__parkingPerf.cls += entry.value
      }).observe({ type: 'layout-shift', buffered: true })
    })
    const scripts = new Map(), errors = [], pending = []
    page.on('pageerror', error => errors.push(error.message))
    page.on('response', response => {
      if (new URL(response.url()).pathname.endsWith('.js')) pending.push(response.body().then(body => scripts.set(response.url(), body)))
    })
    await page.goto(base + '/login', { waitUntil: 'networkidle' })
    await page.getByPlaceholder('请输入密码').waitFor()
    await Promise.all(pending)
    const metrics = await page.evaluate(() => ({
      ...window.__parkingPerf,
      transferredJs: performance.getEntriesByType('resource').filter(r => new URL(r.name).pathname.endsWith('.js')).reduce((sum, r) => sum + r.encodedBodySize, 0),
    }))
    samples.push({ round: round + 1, ...metrics, normalizedGzipJs: [...scripts.values()].reduce((sum, body) => sum + gzipSync(body).length, 0), jsFiles: scripts.size, errors })
    await page.screenshot({ path: `.work/ui/${label}-login.png`, fullPage: true })
    await context.close()
  }
} finally { await browser.close() }
mkdirSync('.work', { recursive: true })
writeFileSync(`.work/web-${label}.json`, JSON.stringify({ label, browserVersion, sampledAt: new Date().toISOString(), viewport: '1440x900', throttling: 'none; local Docker desktop', samples }, null, 2))
console.log(JSON.stringify({ label, samples }))
