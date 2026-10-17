// Counts a complete cold-cache dashboard page in parking-verification only.
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
const require = createRequire(new URL('../vue3/parking-vue/package.json', import.meta.url))
const { chromium } = require('@playwright/test')
const env = Object.fromEntries(readFileSync('.env', 'utf8').split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]))
const compose = ['compose', '-p', 'parking-verification', '-f', 'compose.yml', '-f', 'compose.test.yml']
function snapshot() {
  const input = "SELECT DIGEST,DIGEST_TEXT,COUNT_STAR FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='parking_springboot' AND DIGEST_TEXT LIKE 'SELECT %';"
  const output = execFileSync('docker', [...compose, 'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names parking_springboot'], { input, encoding: 'utf8' })
  return Object.fromEntries(output.trim().split('\n').filter(Boolean).map(line => line.trim().split('\t')).filter(([, sql]) => !/PERFORMANCE_SCHEMA|VERSION_COMMENT/i.test(sql) && sql !== 'SELECT ?').map(([digest, sql, count]) => [digest, { sql, count: Number(count) }]))
}
const browser = await chromium.launch({ headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.goto('http://127.0.0.1:28088/login')
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(env.BOOTSTRAP_ADMIN_USERNAME)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(env.BOOTSTRAP_ADMIN_PASSWORD)
  await page.getByText('管理员登录', { exact: true }).click()
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  await page.waitForURL('**/admin/charts')
  await page.waitForLoadState('networkidle')
  execFileSync('docker', [...compose, 'exec', '-T', 'redis-cache', 'sh', '-c', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB'], { stdio: 'pipe' })
  await page.waitForTimeout(1100) // Let the bounded one-second metadata cache expire too.
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const before = snapshot()
  const started = performance.now()
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('.stat-card').first().waitFor()
  const elapsed = performance.now() - started
  const after = snapshot()
  const statements = Object.entries(after).map(([digest, value]) => ({ sql: value.sql, count: value.count - (before[digest]?.count || 0) })).filter(value => value.count > 0)
  const result = { scope: 'parking-verification', page: '/admin/charts', select_count: statements.reduce((sum, row) => sum + row.count, 0), elapsed_ms: Math.round(elapsed), statements, errors }
  if (errors.length || !result.select_count) throw new Error('Dashboard page probe failed')
  writeFileSync('.work/dashboard-page-query.json', JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ select_count: result.select_count, elapsed_ms: result.elapsed_ms, errors }))
} finally { await browser.close() }
