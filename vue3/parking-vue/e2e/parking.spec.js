import { test, expect } from '@playwright/test'
const stamp = Date.now().toString().slice(-7),
  admin = 'ea' + stamp,
  user = 'eu' + stamp,
  park = 'e2e-' + stamp
let rootToken, adminId, parkId, ruleId
const tokenHeaders = (token) => ({ Authorization: token })
async function data(response) {
  const json = await response.json()
  expect(json.code, json.message).toBe(0)
  return json.data
}
test.beforeAll(async ({ request }) => {
  rootToken = await data(
    await request.post('/api/admin/login', {
      form: {
        username: process.env.BOOTSTRAP_ADMIN_USERNAME,
        password: process.env.BOOTSTRAP_ADMIN_PASSWORD,
      },
    }),
  )
  await data(
    await request.post('/api/admin/addAdmin', {
      headers: tokenHeaders(rootToken),
      data: { admin_name: admin, permissions: 0 },
    }),
  )
  adminId = (
    await data(await request.get('/api/admin/list', { headers: tokenHeaders(rootToken) }))
  ).find((a) => a.admin_name === admin).id
  await data(
    await request.post('/api/user/addUser', {
      headers: tokenHeaders(rootToken),
      data: {
        user_name: user,
        name: '测试用户',
        plate_num: '京A-' + stamp.slice(-6),
        car_color: '白色',
        tel_num: '138' + stamp.padStart(8, '0'),
      },
    }),
  )
  await data(
    await request.post('/api/rule/create', {
      headers: tokenHeaders(rootToken),
      data: { rule_name: park, free_time: 10, charging_unit: 30, unit_cost: 2 },
    }),
  )
  ruleId = (
    await data(
      await request.get('/api/rule/select', {
        headers: tokenHeaders(rootToken),
        params: { rule_name: park },
      }),
    )
  )[0].id
  await data(
    await request.post('/api/parking/rootAddParking', {
      headers: tokenHeaders(rootToken),
      data: {
        park_name: park,
        park_address: '隔离端到端测试',
        park_storey: 1,
        admin_id: adminId,
        rule_id: ruleId,
      },
    }),
  )
  parkId = (
    await data(await request.get('/api/parking/catalog', { headers: tokenHeaders(rootToken) }))
  ).find((p) => p.park_name === park).id
  await data(
    await request.post('/api/spaces/addSpace', {
      headers: tokenHeaders(rootToken),
      data: {
        park_name: park,
        spaces_name: 'A1',
        spaces_row: 1,
        spaces_col: 1,
        spaces_storey: 1,
        spaces_flag: 0,
      },
    }),
  )
})
async function login(page, name, password, isAdmin = false) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(name)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password)
  if (isAdmin) await page.getByText('管理员登录', { exact: true }).click()
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  await expect(page).toHaveURL(isAdmin ? /\/admin\// : /\/user\//)
}
test('super administrator can search and sees validated user forms', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await login(
    page,
    process.env.BOOTSTRAP_ADMIN_USERNAME,
    process.env.BOOTSTRAP_ADMIN_PASSWORD,
    true,
  )
  await page.getByRole('link', { name: '用户管理' }).click()
  await expect(page.getByRole('heading', { name: '用户管理', exact: true })).toBeVisible()
  await page.getByPlaceholder('请输入用户名').fill(user)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('cell', { name: user, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新增用户', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '用户名', exact: true }).fill('a')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog.getByText('长度为3至16位')).toBeVisible()
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true)
    await page.screenshot({
      path: `../../.work/ui/admin-${width}.png`,
      fullPage: true,
      animations: 'disabled',
    })
  }
  expect(errors).toEqual([])
})
test.describe('high density canvas interaction', () => {
  test.use({ deviceScaleFactor: 2 })
  test('selects the correct space after zooming and dragging', async ({ page }) => {
    await login(page, user, user)
    await page.getByRole('combobox', { name: '停车场', exact: true }).press('Enter')
    await page.getByRole('option', { name: park, exact: true }).click()
    await expect(page.getByRole('cell', { name: 'A1', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '放大地图', exact: true }).click()
    await page.getByRole('button', { name: '放大地图', exact: true }).click()
    const canvas = page.locator('canvas')
    const bounds = await canvas.boundingBox()
    await page.mouse.move(bounds.x + 200, bounds.y + 200)
    await page.mouse.down()
    await page.mouse.move(bounds.x + 220, bounds.y + 210, { steps: 5 })
    await page.mouse.up()
    const point = { x: 42 + 29 * 1.2, y: 32 + 29 * 1.2 }
    await canvas.click({ position: point })
    await expect(page.locator('.selection-actions strong')).toHaveText('1')
    await canvas.click({ position: point })
    await expect(page.locator('.selection-actions strong')).toHaveText('0')
  })
})
test('ordinary admin is scoped and cannot open platform management', async ({ page, request }) => {
  await login(page, admin, admin, true)
  await expect(page.getByRole('link', { name: '用户管理' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '充值管理' })).toHaveCount(0)
  await page.goto('/admin/userManagement')
  await expect(page).toHaveURL(/\/admin\/charts/)
  const auth = await data(
    await request.post('/api/admin/login', { form: { username: admin, password: admin } }),
  )
  expect((await request.get('/api/user/pageList', { headers: tokenHeaders(auth) })).status()).toBe(
    403,
  )
})
test('user reserves once, cancels, and sees responsive pages and current account', async ({
  page,
}) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await login(page, user, user)
  await page.getByRole('combobox', { name: '停车场', exact: true }).press('Enter')
  await page.getByRole('option', { name: park, exact: true }).click()
  await page.getByRole('button', { name: '选择', exact: true }).first().click()
  await page.getByRole('button', { name: '确认预约', exact: true }).click()
  await expect(page.getByText(/预约到期：/)).toBeVisible()
  await page.reload()
  await expect(page.getByText(/预约到期：/)).toBeVisible()
  await page.getByRole('button', { name: '取消预约', exact: true }).click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(page.getByText(/预约到期：/)).toHaveCount(0)
  await page.goto('/user/enterHistory')
  await expect(page.getByText('取消或过期', { exact: true })).toBeVisible()
  await page.goto('/user/spaceReservation')
  await expect(page.getByRole('heading', { name: '车位预约', exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true)
    await page.screenshot({
      path: `../../.work/ui/user-${width}.png`,
      fullPage: true,
      animations: 'disabled',
    })
  }
  await page.goto('/user/account')
  await expect(page.getByRole('heading', { name: '个人账户', exact: true })).toBeVisible()
  await expect(page.getByText('支付宝尚未启用，请联系管理员配置。')).toBeVisible()
  await expect(page.getByRole('button', { name: '支付宝充值', exact: true })).toBeDisabled()
  expect(errors).toEqual([])
})
