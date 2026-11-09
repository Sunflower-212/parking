import { test, expect } from '@playwright/test'
const base = `${(process.env.E2E_APP_BASE || '').replace(/\/$/, '')}/`
const token = `e30.${Buffer.from(JSON.stringify({ claims: { id: 1, permissions: -1, username: 'close-fixture' }, exp: 4102444800 })).toString('base64url')}.fixture`
async function setup(page) {
  const state = {
    closes: 0,
    paid: false,
    fail: false,
    lost: false,
    release: null,
    requests: [],
    errors: [],
  }
  const rows = [
    { id: 3, flag: 2, payment_state: 'REVIEW', amounts: 100 },
    { id: 17, flag: 0, payment_state: 'REVIEW', amounts: 10000 },
    { id: 18, flag: 0, payment_state: 'VERIFYING', amounts: 100000 },
  ].map((row) => ({
    ...row,
    pay_method: 0,
    business_type: 'NORMAL_RECHARGE',
    description: '普通充值',
  }))
  page.on('pageerror', (e) => state.errors.push(e.message))
  await page.addInitScript((value) => sessionStorage.setItem('parking-token', value), token)
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.split('/api/')[1]
    state.requests.push(path)
    let data
    if (path === 'user/userInfo')
      data = { id: 1, user_name: 'close-fixture', balance: 100, status: 0 }
    else if (path === 'recharges/userPageList') data = { total: rows.length, items: rows }
    else if (path === 'system/capabilities') data = { alipay: true }
    else if (path === 'user/membership/plans') data = []
    else if (path === 'user/membership/orders/current') data = null
    else if (path === 'user/cancelRecharges') {
      state.closes++
      const id = Number(new URLSearchParams(route.request().postData()).get('rechargesId'))
      if (state.release) await new Promise((resolve) => (state.release = resolve))
      if (state.fail) return route.fulfill({ json: { code: 1, message: '测试关单失败' } })
      const row = rows.find((r) => r.id === id)
      row.flag = state.paid ? 1 : 2
      if (state.lost) return route.abort('connectionreset')
      data = { id, paymentState: state.paid ? 'PAID' : 'CLOSED' }
    } else if (/^user\/recharge\/\d+\/(status|check)$/.test(path)) {
      const id = Number(path.split('/')[2]),
        row = rows.find((r) => r.id === id)
      data = {
        id,
        amounts: row.amounts,
        paymentState: row.flag === 2 ? 'CLOSED' : row.flag === 1 ? 'PAID' : row.payment_state,
        canClose: row.flag === 0,
      }
    } else
      return route.fulfill({ status: 500, json: { code: 1, message: 'unexpected fixture route' } })
    return route.fulfill({ json: { code: 0, data } })
  })
  return state
}
for (const width of [375, 768, 1440])
  test(`manual close confirms once, preserves filters and returns to history at ${width}px`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    const state = await setup(page)
    await page.goto(`${base}user/rechargeHistory`)
    await expect(page.getByText('已关闭', { exact: true })).toBeVisible()
    await page.getByPlaceholder('请输入金额').fill('100')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.getByRole('button', { name: '手动关单', exact: true })).toHaveCount(2)
    await page.getByRole('button', { name: '手动关单', exact: true }).first().click()
    const confirm = page.getByRole('dialog', { name: '确认手动关单' })
    await expect(confirm).toContainText('#17')
    await expect(confirm).toContainText('10,000.00')
    await page.getByRole('button', { name: '暂不关闭', exact: true }).click()
    expect(state.closes).toBe(0)
    await page.getByRole('button', { name: '手动关单', exact: true }).first().click()
    await expect(confirm).toBeVisible()
    await page.screenshot({ path: info.outputPath(`close-confirm-${width}.png`), fullPage: true, animations: 'disabled' })
    state.lost = true
    await page.getByRole('button', { name: '确认关单', exact: true }).click()
    await expect(page.getByRole('button', { name: '手动关单', exact: true })).toHaveCount(1)
    expect(state.closes).toBe(1)
    await expect(page.getByPlaceholder('请输入金额')).toHaveValue('100')
    await expect(page.getByText('已关闭', { exact: true })).toHaveCount(2)
    await page.getByRole('button', { name: '核验到账', exact: true }).click()
    await expect(page.getByRole('button', { name: '返回充值记录', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '返回充值记录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`${base}user/rechargeHistory$`))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(state.errors).toEqual([])
  })
test('close failure leaves the row actionable and repeated click does not create concurrent requests', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto(`${base}user/rechargeHistory`)
  state.fail = true
  state.release = true
  await page.getByRole('button', { name: '手动关单', exact: true }).first().click()
  await page.getByRole('button', { name: '确认关单', exact: true }).click()
  await expect.poll(() => state.closes).toBe(1)
  await expect(page.getByRole('button', { name: '手动关单', exact: true }).first()).toBeDisabled()
  state.release()
  state.release = null
  await expect(page.getByRole('button', { name: '手动关单', exact: true }).first()).toBeEnabled()
  await expect(page.getByRole('button', { name: '手动关单', exact: true })).toHaveCount(2)
  expect(state.closes).toBe(1)
})
