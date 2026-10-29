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

test('super administrator can export filtered users and resume a background task', async ({
  page,
}) => {
  await login(
    page,
    process.env.BOOTSTRAP_ADMIN_USERNAME,
    process.env.BOOTSTRAP_ADMIN_PASSWORD,
    true,
  )
  await page.getByRole('link', { name: '用户管理' }).click()
  await page.getByPlaceholder('请输入用户名').fill(user)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('cell', { name: user, exact: true })).toBeVisible()
  if (process.env.BACKGROUND_TASKS_ENABLED === 'true') {
    await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
    await expect(page.getByText('导出处理中，可离开页面')).toBeVisible()
    await page.reload()
    const downloadButton = page.getByRole('button', { name: '下载文件（保留24小时）' })
    await expect(downloadButton).toBeVisible({ timeout: 30000 })
    const downloaded = page.waitForEvent('download')
    await downloadButton.click()
    expect((await downloaded).suggestedFilename()).toBe('users.csv')
  } else {
    const downloaded = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出 CSV', exact: true }).click()
    expect((await downloaded).suggestedFilename()).toBe('users.csv')
  }
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
    // List focus gives a known camera center regardless of viewport size and DPR.
    await page.getByRole('button', { name: '选择', exact: true }).first().click()
    const point = { x: bounds.width / 2, y: bounds.height / 2 }
    await page.getByRole('button', { name: '清空选择', exact: true }).click()
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
  await expect(page.getByRole('link', { name: '操作日志' })).toHaveCount(0)
  await page.goto('/admin/auditLogs')
  await expect(page).toHaveURL(/\/admin\/charts/)
  await page.goto('/admin/userManagement')
  await expect(page).toHaveURL(/\/admin\/charts/)
  const auth = await data(
    await request.post('/api/admin/login', { form: { username: admin, password: admin } }),
  )
  expect((await request.get('/api/user/pageList', { headers: tokenHeaders(auth) })).status()).toBe(
    403,
  )
  expect(
    (await request.get('/api/admin/audit/page', { headers: tokenHeaders(auth) })).status(),
  ).toBe(403)
})
test('map editor previews, undoes, saves atomically and retains a conflicting draft', async ({
  page,
  request,
}) => {
  await login(page, admin, admin, true)
  await page.goto('/admin/spaceManagement')
  await page.getByRole('combobox', { name: '停车场', exact: true }).press('Enter')
  await page.getByRole('option', { name: park, exact: true }).click()
  await expect(page.getByRole('cell', { name: 'A1', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '编辑地图', exact: true }).click()
  await page.getByRole('button', { name: '批量生成车位', exact: true }).click()
  const generator = page.getByRole('dialog', { name: '批量生成车位', exact: true })
  await generator.getByRole('spinbutton', { name: '起始行', exact: true }).fill('3')
  await generator.getByRole('button', { name: '生成预览' }).click()
  await expect(generator.getByText('预览 10 个车位')).toBeVisible()
  await generator.getByRole('button', { name: '加入草稿' }).click()
  await expect(page.getByText('10 项待保存')).toBeVisible()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.getByText('0 项待保存')).toBeVisible()
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(page.getByText('10 项待保存')).toBeVisible()
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true)
    await page.screenshot({ path: `../../.work/ui/map-editor-${width}.png`, fullPage: true })
  }
  await page.route(
    '**/api/spaces/layout/batch',
    (route) => route.fulfill({ json: { code: 1, message: '隔离测试：保存暂时失败' } }),
    { times: 1 },
  )
  await page.getByRole('button', { name: '预览并保存' }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(page.getByText('隔离测试：保存暂时失败', { exact: true })).toBeVisible()
  await expect(page.getByText('10 项待保存')).toBeVisible()
  await expect(page.getByRole('button', { name: '预览并保存' })).toBeEnabled()
  let saves = 0
  page.on('request', (req) => {
    if (req.url().endsWith('/spaces/layout/batch')) saves++
  })
  await page.getByRole('button', { name: '预览并保存' }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(page.getByText('地图已整批保存')).toBeVisible()
  expect(saves).toBe(1)
  await page.getByRole('button', { name: '编辑地图', exact:true }).click()
  await page.getByRole('row').filter({has:page.getByRole('cell',{name:'A001',exact:true})}).getByRole('button',{name:'选择',exact:true}).click()
  async function dragAcrossThree() {
    await page.locator('canvas').scrollIntoViewIfNeeded()
    const bounds=await page.locator('canvas').boundingBox()
    const cellWidth=58*parseInt(await page.locator('.map-controls > span').innerText())/100
    const center={x:bounds.x+bounds.width/2,y:bounds.y+bounds.height/2}
    await page.mouse.move(center.x,center.y);await page.mouse.down()
    await page.mouse.move(center.x+2*cellWidth,center.y,{steps:8});await page.mouse.up()
  }
  await page.getByRole('button',{name:/墙体/}).click()
  await dragAcrossThree()
  await expect(page.getByText('3 项待保存')).toBeVisible()
  await page.getByRole('button',{name:'撤销',exact:true}).click()
  await expect(page.getByText('0 项待保存')).toBeVisible()
  await page.getByRole('button',{name:/选择 \/ 框选/}).click()
  await dragAcrossThree()
  await expect(page.locator('.selection-actions strong')).toHaveText('3')
  await page.getByRole('button',{name:'退出编辑',exact:true}).click()
  rootToken = await data(
    await request.post('/api/admin/login', {
      form: {
        username: process.env.BOOTSTRAP_ADMIN_USERNAME,
        password: process.env.BOOTSTRAP_ADMIN_PASSWORD,
      },
    }),
  )
  const snapshot = await data(
    await request.get('/api/spaces/editor', {
      headers: tokenHeaders(rootToken),
      params: { park_id: parkId, spaces_storey: 1 },
    }),
  )
  expect(snapshot.items).toHaveLength(11)
  await page.getByRole('button', { name: '编辑地图', exact: true }).click()
  await page.getByRole('button', { name: '选择', exact: true }).first().click()
  await page.getByRole('textbox', { name: '位置名称', exact: true }).fill('我的草稿')
  await page.getByRole('button', { name: '应用到草稿' }).click()
  await data(
    await request.post('/api/spaces/layout/batch', {
      headers: tokenHeaders(rootToken),
      data: {
        parkId,
        floor: 1,
        baseRevision: snapshot.revision,
        creates: [],
        updates: [{ id: snapshot.items[0].id, name: '其他人修改', type: 0 }],
        deleteIds: [],
      },
    }),
  )
  await page.getByRole('button', { name: '预览并保存' }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(page.locator('.conflict-alert')).toContainText(/地图已被.*修改/)
  await expect(page.getByRole('cell', { name: '我的草稿', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '预览并保存' })).toBeDisabled()
  await page.getByRole('button', { name: '退出编辑', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByText('1 项待保存')).toBeVisible()
  await page.getByRole('button', { name: '退出编辑', exact: true }).click()
  await page.getByRole('button', { name: '放弃草稿', exact: true }).click()
  await expect(page.getByRole('cell', { name: '其他人修改', exact: true })).toBeVisible()
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
for (const root of [false, true]) {
  test(`${root ? 'super' : 'ordinary'} administrator can manage parking records`, async ({
    page,
  }) => {
    await login(
      page,
      root ? process.env.BOOTSTRAP_ADMIN_USERNAME : admin,
      root ? process.env.BOOTSTRAP_ADMIN_PASSWORD : admin,
      true,
    )
    await page.getByRole('link', { name: /停车场管理$/ }).click()
    await expect(page.getByRole('heading', { name: '停车场管理', exact: true })).toBeVisible()
    await expect(page.locator('.el-alert--error')).toHaveCount(0)
    const name = park + (root ? '-r' : '-a')
    await page.getByRole('button', { name: '新增停车场', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增记录', exact: true })
    await dialog.getByRole('textbox', { name: '停车场名称', exact: true }).fill(name)
    await dialog.getByRole('textbox', { name: '停车场地址', exact: true }).fill('停车场回归地址')
    if (root) {
      await dialog.getByRole('combobox', { name: '所属管理员', exact: true }).press('Enter')
      await page.getByRole('option', { name: admin, exact: true }).click()
    }
    await dialog.getByRole('combobox', { name: '计费规则', exact: true }).press('Enter')
    await page.getByRole('option', { name: park, exact: true }).click()
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await page.getByPlaceholder('请输入停车场', { exact: true }).fill(name)
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name, exact: true }) })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    const edit = page.getByRole('dialog', { name: '编辑记录', exact: true })
    await edit.getByRole('textbox', { name: '停车场地址', exact: true }).fill('已更新回归地址')
    await edit.getByRole('button', { name: '保存', exact: true }).click()
    await expect(edit).not.toBeVisible()
    await expect(row.getByRole('cell', { name: '已更新回归地址', exact: true })).toBeVisible()
    await row.getByRole('button', { name: '删除', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
    await expect(page.getByRole('cell', { name, exact: true })).toHaveCount(0)
  })
}
test('parking option load failure has a retry and prevents invalid creation', async ({ page }) => {
  await login(page, admin, admin, true)
  await page.route('**/api/rule/select', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 1, message: '测试服务暂不可用' }),
    }),
  )
  await page.getByRole('link', { name: /停车场管理$/ }).click()
  await expect(page.getByText('停车场基础选项加载失败，请重试', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增停车场', exact: true })).toBeDisabled()
  await page.unroute('**/api/rule/select')
  await page.getByRole('button', { name: '重新加载选项', exact: true }).click()
  await expect(page.getByRole('button', { name: '新增停车场', exact: true })).toBeEnabled()
})
for (const isAdmin of [false, true]) {
  test(`${isAdmin ? 'administrator' : 'user'} logout is compact and preserves the session on cancellation or service failure`, async ({
    page,
  }) => {
    await login(page, isAdmin ? admin : user, isAdmin ? admin : user, isAdmin)
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      await page.getByRole('button', { name: '退出登录', exact: true }).click()
      const box = page.locator('.el-message-box')
      await expect(box).toBeVisible()
      await expect
        .poll(async () => Math.abs((await box.boundingBox()).width - Math.min(400, width - 32)))
        .toBeLessThan(1)
      await box.getByRole('button', { name: '取消', exact: true }).click()
      await expect(box).not.toBeVisible()
      await expect(page).not.toHaveURL(/\/login/)
    }
    const endpoint = '**/api/' + (isAdmin ? 'admin' : 'user') + '/logout'
    await page.route(endpoint, (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 1, message: '测试退出服务暂不可用' }),
      }),
    )
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '退出', exact: true }).click()
    await expect(page.getByText('测试退出服务暂不可用', { exact: true })).toBeVisible()
    await page.reload()
    await expect(page).not.toHaveURL(/\/login/)
    await page.unroute(endpoint)
    let calls = 0
    page.on('request', (req) => {
      if (req.url().endsWith('/logout')) calls++
    })
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '退出', exact: true }).click()
    await expect(page).toHaveURL(/\/login/)
    expect(calls).toBe(1)
  })
}
