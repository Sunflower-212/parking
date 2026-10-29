import { test, expect } from '@playwright/test'
const user = 'notice' + Date.now().toString().slice(-8)
async function login(page, admin = true) {
  await page.goto('/login')
  await page
    .getByPlaceholder('请输入账号', { exact: true })
    .fill(admin ? process.env.BOOTSTRAP_ADMIN_USERNAME : user)
  await page
    .getByPlaceholder('请输入密码', { exact: true })
    .fill(admin ? process.env.BOOTSTRAP_ADMIN_PASSWORD : user)
  if (admin) await page.getByText('管理员登录', { exact: true }).click()
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  await expect(page).toHaveURL(admin ? /\/admin\// : /\/user\//)
  await expect(page.locator('.el-message--success').filter({ hasText: '登录成功' })).toBeVisible()
  await page
    .locator('.el-message__closeBtn')
    .evaluateAll((nodes) => nodes.forEach((n) => n.click()))
  await expect(page.locator('.el-message--success')).toHaveCount(0)
}
test.beforeAll(async ({ request }) => {
  const login = await request.post('/api/admin/login', {
    form: {
      username: process.env.BOOTSTRAP_ADMIN_USERNAME,
      password: process.env.BOOTSTRAP_ADMIN_PASSWORD,
    },
  })
  const token = (await login.json()).data
  const response = await request.post('/api/user/addUser', {
    headers: { Authorization: token },
    data: {
      user_name: user,
      name: '反馈测试',
      plate_num: '京F-' + Date.now().toString().slice(-6),
      car_color: '白色',
    },
  })
  expect((await response.json()).code).toBe(0)
})
test('login failures stay at the top at three widths with expandable and copyable request ID', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.route('**/api/user/login', (route) =>
    route.fulfill({
      headers: { 'x-request-id': 'notice-login' },
      json: { code: 1, message: '账号或密码错误' },
    }),
  )
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/login')
    await page.getByPlaceholder('请输入账号', { exact: true }).fill('noticeuser')
    await page.getByPlaceholder('请输入密码', { exact: true }).fill('wrongpassword')
    await page.getByRole('button', { name: /^登\s*录$/ }).click()
    const message = page.locator('.el-message--error')
    await expect(message).toContainText('账号或密码错误')
    const bounds = await message.boundingBox()
    expect(bounds.y).toBeLessThan(100)
    expect(bounds.x).toBeGreaterThanOrEqual(0)
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width)
    await expect(page.getByText('请求 ID：notice-login')).not.toBeVisible()
    await message.getByText('查看详情', { exact: true }).click()
    await expect(message.getByText('请求 ID：notice-login')).toBeVisible()
    await message.getByRole('button', { name: '复制请求 ID' }).click()
    await expect
      .poll(() => page.evaluate(() => navigator.clipboard.readText()))
      .toBe('notice-login')
    await page.screenshot({ path: `../../.work/ui/feedback-login-${width}.png`, fullPage: true })
  }
})
test('save errors remain above the dialog, retain inputs and distinguish a successful save from failed refresh', async ({
  page,
}) => {
  await login(page)
  await page.getByRole('link', { name: /用户管理$/ }).click()
  await page.getByRole('button', { name: '新增用户', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增记录' })
  await dialog.getByRole('textbox', { name: '用户名', exact: true }).fill('retained-user')
  await page.route(
    '**/api/user/addUser',
    (route) =>
      route.fulfill({
        headers: { 'x-request-id': 'notice-save' },
        json: { code: 1, message: '用户名已存在' },
      }),
    { times: 1 },
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  const error = page.locator('.el-message--error')
  await expect(error).toContainText('用户名已存在')
  await expect
    .poll(() =>
      error.evaluate((node) => {
        const r = node.getBoundingClientRect()
        return (
          document
            .elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
            ?.closest('.el-message') === node
        )
      }),
    )
    .toBe(true)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('textbox', { name: '用户名', exact: true })).toHaveValue(
    'retained-user',
  )
  await expect(page.locator('.el-message--success')).toHaveCount(0)
  await error.locator('.el-message__closeBtn').click()
  await page.route('**/api/user/addUser', (route) => route.fulfill({ json: { code: 0 } }))
  await page.route('**/api/user/pageList*', (route) =>
    route.fulfill({ status: 503, json: { code: 1, message: '列表服务暂不可用' } }),
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.locator('.el-message--warning')).toContainText('保存成功，列表刷新失败')
  await expect(page.locator('.el-message--error')).toHaveCount(0)
})
test('password reset cancellation is quiet, success is explicit, and failed deletion keeps the row', async ({
  page,
}) => {
  await login(page)
  await page.getByRole('link', { name: /用户管理$/ }).click()
  await page.getByPlaceholder('请输入用户名').fill(user)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  const row = page
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: user, exact: true }) })
  await expect(row).toBeVisible()
  let resetCalls = 0
  await page.route('**/api/user/resetPassword', (route) => {
    resetCalls++
    return route.fulfill({ json: { code: 0 } })
  })
  await row.getByRole('button', { name: '重置密码', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
  expect(resetCalls).toBe(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await row.getByRole('button', { name: '重置密码', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.locator('.el-message--success')).toContainText('密码已重置')
  await page.route('**/api/user/delete?*', (route) =>
    route.fulfill({ json: { code: 1, message: '存在关联业务记录，无法删除' } }),
  )
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('存在关联业务记录')
  await expect(row).toBeVisible()
})
test('membership failure does not claim success and later successful renewal refreshes the account', async ({
  page,
}) => {
  await login(page, false)
  await page.goto('/user/account')
  await page.route(
    /\/api\/user\/(getVip|vipRenewal)$/,
    (route) => route.fulfill({ json: { code: 1, message: '会员操作暂不可用' } }),
    { times: 1 },
  )
  await page.getByRole('button', { name: '确认开通', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('会员操作暂不可用')
  await expect(page.locator('.el-message--success')).toHaveCount(0)
  await page.getByRole('button', { name: '确认开通', exact: true }).click()
  await expect(page.locator('.el-message--success')).toContainText('会员信息已更新')
  await expect(page.getByRole('button', { name: '确认续期', exact: true })).toBeVisible()
})
test('export JSON errors never become a download and valid CSV announces download start', async ({
  page,
}) => {
  await login(page)
  await page.getByRole('link', { name: /用户管理$/ }).click()
  await page.route('**/api/admin/background/capabilities', (route) =>
    route.fulfill({ json: { code: 0, data: { exports: false } } }),
  )
  let downloads = 0
  page.on('download', () => downloads++)
  await page.route(
    '**/api/user/export*',
    (route) =>
      route.fulfill({
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: 1, message: '导出文件已过期' }),
      }),
    { times: 1 },
  )
  await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('导出文件已过期')
  expect(downloads).toBe(0)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
  await download
  await expect(page.locator('.el-message--success')).toContainText('已开始下载')
  expect(downloads).toBe(1)
})
test('corrupt images explain decode failure without making a vehicle request', async ({ page }) => {
  await login(page)
  await page.route('**/api/system/capabilities', (route) =>
    route.fulfill({ json: { code: 0, data: { ocr: true } } }),
  )
  await page.goto('/admin/entAndExitManagement')
  await expect(page.getByRole('button', { name: '选择图片', exact: true }).first()).toBeEnabled()
  let writes = 0
  page.on('request', (req) => {
    if (/\/(enter|orders)\/upload$/.test(req.url())) writes++
  })
  await page
    .locator('input[type=file]')
    .first()
    .setInputFiles({ name: 'corrupt.png', mimeType: 'image/png', buffer: Buffer.from('not a PNG') })
  await expect(page.locator('.el-message--error')).toContainText('图片无法读取')
  expect(writes).toBe(0)
})
