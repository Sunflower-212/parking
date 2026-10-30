import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const base = process.env.E2E_APP_BASE || '/'
const evidence = resolve('../../.work/payment-lifecycle/ui')
mkdirSync(evidence, { recursive: true })
const token = `e30.${Buffer.from(JSON.stringify({ claims: { id: 1, permissions: -1, username: 'payment-fixture' }, exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.fixture`
async function setup(page, { loggedIn = true, providerUnavailable = false } = {}) {
  const errors = [],
    unexpected = []
  let paid = false,
    credited = false,
    checks = 0,
    creates = 0
  page.on('pageerror', (e) => errors.push(e.message))
  if (loggedIn)
    await page.addInitScript((value) => sessionStorage.setItem('parking-token', value), token)
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.split('/api/')[1]
    let data
    if (path === 'user/login') data = token
    else if (path === 'user/userInfo')
      data = { id: 1, user_name: 'payment-fixture', status: 0, balance: credited ? 30 : 20 }
    else if (path === 'system/capabilities') data = { alipay: true }
    else if (path === 'user/recharge') {
      creates++
      data = {
        rechargeId: 7,
        reAmounts: 10,
        merchantOrderNo: 'PKfixture',
        expiresAt: '2099-01-01T12:30:00',
        form: '<form action="https://openapi-sandbox.dl.alipaydev.com/gateway.do" method="post"><input type="hidden" name="out_trade_no" value="PKfixture"></form>',
      }
    } else if (path === 'user/recharge/7/check' || path === 'user/recharge/7/status') {
      if (path.endsWith('/check')) {
        checks++
        if (paid && !providerUnavailable) credited = true
      }
      data = {
        id: 7,
        amounts: 10,
        paymentState: credited ? 'PAID' : 'VERIFYING',
        canCheck: !credited,
      }
    } else if (path === 'recharges/userPageList')
      data = {
        total: 1,
        items: [
          {
            id: 7,
            amounts: 10,
            flag: 2,
            payment_state: 'VERIFYING',
            create_time: '2026-10-30T21:52:51',
          },
        ],
      }
    else {
      unexpected.push(path)
      return route.fulfill({ status: 500, json: { code: 1, message: '隔离测试未配置的请求' } })
    }
    return route.fulfill({ json: { code: 0, data } })
  })
  // The sandbox origin is intercepted: no Alipay request or payment is sent.
  await page.route('https://openapi-sandbox.dl.alipaydev.com/**', async (route) => {
    paid = true
    const origin = new URL(page.url()).origin
    const returnTo = `${origin}${base}user/paymentResult?rechargeId=7&out_trade_no=PKfixture&trade_status=TRADE_SUCCESS`
    return route.fulfill({
      contentType: 'text/html;charset=UTF-8',
      body: `<html><head><meta charset="UTF-8"></head><body><button onclick='location.href=${JSON.stringify(returnTo)}'>完成付款并返回</button></body></html>`,
    })
  })
  return {
    errors,
    unexpected,
    markPaid: () => {
      paid = true
    },
    get checks() {
      return checks
    },
    get creates() {
      return creates
    },
  }
}

for (const width of [375, 768, 1440]) {
  test(`same-tab sandbox return confirms credit and leaves one account page at ${width}px`, async ({
    page,
    context,
  }) => {
    await page.setViewportSize({ width, height: 1000 })
    const fixture = await setup(page)
    await page.goto(`${base}user/account`)
    await page.getByRole('spinbutton', { name: '充值金额 / 元' }).fill('10')
    await page.getByRole('button', { name: '支付宝充值', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '支付宝沙箱支付' })).toBeVisible()
    await page.getByRole('button', { name: '前往支付宝沙箱', exact: true }).click()
    await expect(page).toHaveURL(/openapi-sandbox/)
    expect(context.pages()).toHaveLength(1)
    await page.getByRole('button', { name: '完成付款并返回' }).click()
    await expect(page).toHaveURL(new RegExp(`${base}user/account$`))
    await expect(
      page.locator('.account-grid section').first().locator('strong').first(),
    ).toContainText('30.00')
    expect(context.pages()).toHaveLength(1)
    expect(fixture.creates).toBe(1)
    expect(fixture.checks).toBe(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await expect(page.locator('.profile')).toContainText('普通用户')
    await page.screenshot({
      path: resolve(evidence, `payment-account-${width}.png`),
      animations: 'disabled',
      fullPage: true,
    })
    expect(fixture.errors).toEqual([])
    expect(fixture.unexpected).toEqual([])
  })
}

test('a successful return parameter cannot show successful credit while verification is pending', async ({
  page,
}) => {
  const fixture = await setup(page, { providerUnavailable: true })
  await page.goto(`${base}user/paymentResult?rechargeId=7&trade_status=TRADE_SUCCESS`)
  await expect(page.locator('.breadcrumb strong')).toHaveText('充值结果')
  await expect(page.getByRole('heading', { name: '充值结果', exact: true })).toBeVisible()
  const svgIcon = page.locator('link[rel="icon"][type="image/svg+xml"]')
  await expect(svgIcon).toHaveAttribute('href', `${base}parking-favicon.svg`)
  const iconResponse = await page.request.get(await svgIcon.getAttribute('href'))
  expect(iconResponse.ok()).toBe(true)
  expect(iconResponse.headers()['content-type']).toContain('image/svg+xml')
  const icoResponse = await page.request.get(`${base}parking-favicon.ico`)
  expect(icoResponse.ok()).toBe(true)
  const ico = await icoResponse.body()
  expect(ico.readUInt16LE(2)).toBe(1)
  expect(ico.readUInt16LE(4)).toBe(3)
  expect([0, 1, 2].map(i => ico[6 + i * 16])).toEqual([16, 32, 48])
  await expect(page.getByText('到账确认中', { exact: true })).toBeVisible()
  await expect(page.getByText('充值成功，余额已更新', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: resolve(evidence, 'payment-pending.png'), fullPage: true, animations: 'disabled' })
  expect(fixture.checks).toBe(1)
  expect(fixture.errors).toEqual([])
})

test('a historical locally cancelled recharge can be verified from its own history row', async ({
  page,
}) => {
  const fixture = await setup(page)
  fixture.markPaid()
  await page.goto(`${base}user/rechargeHistory`)
  await page.getByRole('button', { name: '核验到账', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`${base}user/account$`))
  await expect(
    page.locator('.account-grid section').first().locator('strong').first(),
  ).toContainText('30.00')
  expect(fixture.checks).toBe(1)
  expect(fixture.unexpected).toEqual([])
})

test('expired login resumes only the fixed owned-payment result route', async ({ page }) => {
  const fixture = await setup(page, { loggedIn: false })
  fixture.markPaid()
  await page.goto(`${base}user/paymentResult?rechargeId=7`)
  await expect(page).toHaveURL(/\/login$/)
  await page.getByPlaceholder('请输入账号', { exact: true }).fill('payment-fixture')
  await page.getByPlaceholder('请输入密码', { exact: true }).fill('test-only')
  await page.getByRole('button', { name: /^登\s*录$/ }).click()
  await expect(page).toHaveURL(new RegExp(`${base}user/account$`))
  expect(fixture.checks).toBe(1)
  expect(fixture.errors).toEqual([])
})

test('legacy account return is replaced by verification without creating another tab', async ({
  page,
  context,
}) => {
  const fixture = await setup(page)
  fixture.markPaid()
  await page.goto(`${base}user/account?out_trade_no=7`)
  await expect(page).toHaveURL(new RegExp(`${base}user/account$`))
  await expect(
    page.locator('.account-grid section').first().locator('strong').first(),
  ).toContainText('30.00')
  expect(context.pages()).toHaveLength(1)
  expect(fixture.checks).toBe(1)
})
