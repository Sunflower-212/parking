// Only operates on the isolated verification stack and its synthetic administrator login.
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'
const require = createRequire(new URL('../vue3/parking-vue/package.json', import.meta.url))
const { chromium } = require('@playwright/test')
const label = process.argv[2] || 'candidate'
if (!/^[a-z-]+$/.test(label)) throw new Error('Invalid result label')
const env = Object.fromEntries(readFileSync('.env', 'utf8').split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]))
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage()
try {
  await page.goto('http://127.0.0.1:28088/login')
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(env.BOOTSTRAP_ADMIN_USERNAME)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(env.BOOTSTRAP_ADMIN_PASSWORD)
  await page.getByText('管理员登录', { exact: true }).click()
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  await page.waitForURL(/\/admin\//)
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  const samples = []
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    const dialog = page.locator('.el-message-box')
    await dialog.waitFor()
    await page.waitForTimeout(250)
    samples.push({ viewport: width, ...(await dialog.boundingBox()) })
    await dialog.getByRole('button', { name: '取消', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
  }
  writeFileSync(`.work/dialog-${label}.json`, JSON.stringify(samples, null, 2))
  console.log(JSON.stringify({ label, samples }))
} finally { await browser.close() }
