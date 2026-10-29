import { test, expect } from '@playwright/test'

async function login(page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(process.env.BOOTSTRAP_ADMIN_USERNAME)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(process.env.BOOTSTRAP_ADMIN_PASSWORD)
  await page.getByText('管理员登录', { exact: true }).click()
  const response = page.waitForResponse(r => r.url().endsWith('/api/admin/login'))
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  const id = (await response).headers()['x-request-id']
  await expect(page).toHaveURL(/\/admin\//)
  return id
}

test('superadmin filters, paginates, inspects, copies and resets audit records at three widths', async ({ page, context, request }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  const errors = []; page.on('pageerror', e => errors.push(e.message))
  const id = await login(page)
  // Ensure a second page exists even on a newly initialized isolated database.
  for (let i = 0; i < 21; i++) await request.post('/api/admin/login', { form: { username: 'auditMissing', password: 'invalidTest' } })
  await page.getByRole('link', { name: /操作日志$/ }).click()
  await expect(page.getByRole('heading', { name: '操作日志', exact: true })).toBeVisible()
  await expect(page.locator('.el-pagination .btn-next')).toBeEnabled()
  const nextPage = page.waitForResponse(r => r.url().includes('/api/admin/audit/page?') && r.url().includes('pageNum=2'))
  await page.locator('.el-pagination .btn-next').click()
  expect((await (await nextPage).json()).data.items.length).toBeGreaterThan(0)
  await page.getByPlaceholder('精确请求 ID').fill(id)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('cell', { name: id, exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '管理员登录', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '详情', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '操作日志详情' })
  await expect(dialog.getByText(id, { exact: false })).toBeVisible()
  await dialog.getByRole('button', { name: '复制', exact: true }).click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(id)
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../../.work/ui/audit-detail-${width}.png`, fullPage: true })
  }
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(page.getByPlaceholder('精确请求 ID')).toHaveValue('')
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../../.work/ui/audit-page-${width}.png`, fullPage: true })
  }
  expect(errors).toEqual([])
})

test('audit load failures expose retry and correlation ID without clearing login', async ({ page }) => {
  await login(page)
  await page.route('**/api/admin/audit/page*', route => route.fulfill({ status: 503,
    headers: { 'x-request-id': 'audit-failure-test' }, contentType: 'application/json',
    body: JSON.stringify({ code: 1, message: '审计测试服务暂不可用' }) }))
  await page.goto('/admin/auditLogs')
  await expect(page.getByText('请求 ID：audit-failure-test')).toBeVisible()
  await expect(page.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
  await page.unroute('**/api/admin/audit/page*')
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByRole('button', { name: '详情', exact: true }).first()).toBeVisible()
  await expect(page).toHaveURL(/\/admin\/auditLogs/)
})

