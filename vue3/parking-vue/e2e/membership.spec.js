import { test, expect } from '@playwright/test'

const appBase = process.env.E2E_APP_BASE || ''
const plans = [
  { code: 'MONTHLY', name: '月度', price: 188, months: 1 },
  { code: 'QUARTERLY', name: '季度', price: 488, months: 3 },
  { code: 'YEARLY', name: '年度', price: 1888, months: 12 },
]

async function fixture(page, baseURL, balance = 100) {
  const origin = new URL(baseURL).origin
  const account = {
    id: 1,
    user_name: '会员验收',
    balance,
    available_balance: balance,
    reserved_balance: 0,
    status: 0,
  }
  const state = { account, order: null, creates: 0, forms: 0, failCreate: false, errors: [] }
  page.on('pageerror', (error) => state.errors.push(error.message))
  await page.addInitScript(() => {
    sessionStorage.setItem(
      'parking-token',
      `e30.${btoa(JSON.stringify({ claims: { id: 1, username: 'member', permissions: -1 }, exp: 4102444800 }))}.test`,
    )
    const submit = HTMLFormElement.prototype.submit
    HTMLFormElement.prototype.submit = function () {
      sessionStorage.setItem('membership-form-target', this.target)
      return submit.call(this)
    }
  })
  function settled() {
    if (state.order.state === 'SUCCEEDED') return
    account.balance = account.balance + state.order.supplementAmount - state.order.price
    account.available_balance = account.balance
    account.reserved_balance = 0
    account.status = 1
    account.vip_start_time = '2026-11-05T12:00:00'
    account.vip_end_time = '2099-12-05T12:00:00'
    state.order.state = 'SUCCEEDED'
    state.order.paymentState = 'PAID'
  }
  const status = () => ({
    id: 9,
    amounts: state.order?.supplementAmount,
    paymentState: state.order?.paymentState,
    businessType: 'MEMBERSHIP_TOPUP',
    membershipOrder: state.order,
  })
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.hostname === 'openapi-sandbox.dl.alipaydev.com') {
      settled()
      return route.fulfill({
        contentType: 'text/html; charset=utf-8',
        body: `<a href="${origin}${appBase}/user/paymentResult?rechargeId=9">返回商户</a>`,
      })
    }
    if (url.origin !== origin) return route.abort()
    if (!url.pathname.includes('/api/')) return route.continue()
    const path = url.pathname.slice(url.pathname.indexOf('/api/') + 4)
    let data
    if (path === '/user/userInfo') data = account
    else if (path === '/system/capabilities') data = { alipay: true }
    else if (path === '/user/membership/plans') data = plans
    else if (path === '/user/membership/orders/current')
      data = state.order?.state === 'PENDING' ? state.order : null
    else if (path === '/user/membership/orders') {
      state.creates++
      if (state.failCreate)
        return route.fulfill({ json: { code: 1, message: '余额核验失败，请重试' } })
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      const plan = plans.find((item) => item.code === route.request().postDataJSON().planCode)
      state.order = {
        id: 'member-order',
        planCode: plan.code,
        planName: plan.name,
        price: plan.price,
        reservedAmount: Math.min(account.available_balance, plan.price),
        supplementAmount: Math.max(0, plan.price - account.available_balance),
        state: 'PENDING',
        rechargeId: 9,
        paymentState: 'PENDING',
        expiresAt: '2099-12-31T12:00:00',
        renewed: false,
      }
      if (state.order.supplementAmount === 0) settled()
      else {
        account.reserved_balance = state.order.reservedAmount
        account.available_balance -= state.order.reservedAmount
      }
      data = state.order
    } else if (path === '/user/membership/orders/member-order/pay') {
      state.forms++
      data = {
        rechargeId: 9,
        reAmounts: state.order.supplementAmount,
        rechargeName: '会员充值补充',
        form: '<form action="https://openapi-sandbox.dl.alipaydev.com/gateway.do" method="post"><input type="hidden" name="test" value="stub"></form>',
      }
    } else if (/^\/user\/recharge\/9\/(check|status)$/.test(path)) data = status()
    else if (path === '/user/cancelRecharges') {
      state.order.state = 'CLOSED'
      state.order.paymentState = 'CLOSED'
      account.reserved_balance = 0
      account.available_balance = account.balance
      data = status()
    } else if (path === '/recharges/userPageList')
      data = {
        total: 3,
        items: [
          {
            id: 3,
            amounts: 188,
            flag: 1,
            payment_state: 'PAID',
            business_type: 'MEMBERSHIP_DEBIT',
            description: '会员充值扣费（月度）',
          },
          {
            id: 2,
            amounts: 88,
            flag: 1,
            payment_state: 'PAID',
            business_type: 'MEMBERSHIP_TOPUP',
            description: '会员充值补充（月度）',
          },
          {
            id: 1,
            amounts: 100,
            flag: 1,
            payment_state: 'PAID',
            business_type: 'NORMAL_RECHARGE',
            description: '普通充值',
          },
        ],
      }
    else return route.fulfill({ status: 404, json: { code: 1, message: '未配置的验收接口' } })
    return route.fulfill({ json: { code: 0, data } })
  })
  await page.goto(`${appBase}/user/account`)
  await expect(page.locator('.membership-plan')).toHaveCount(3)
  return state
}

for (const width of [375, 768, 1440]) {
  test(`membership supplement uses one tab and refreshes identity at ${width}px`, async ({
    page,
    context,
    baseURL,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await fixture(page, baseURL)
    await expect(page.getByText('尚未开通会员', { exact: true })).toHaveCount(0)
    await expect(page.locator('.surface .el-tag')).toHaveText('普通用户')
    expect(await page.locator('.membership-plan').evaluateAll(nodes => nodes.every(node => node.scrollWidth <= node.clientWidth))).toBe(true)
    await page.screenshot({ path: info.outputPath(`account-${width}.png`), fullPage: true, animations: 'disabled' })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('button', { name: /支付宝补差.*88/ }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.screenshot({ path: info.outputPath(`payment-${width}.png`), fullPage: true, animations: 'disabled' })
    await page.getByRole('button', { name: '前往支付宝沙箱' }).click()
    await page.getByRole('link', { name: '返回商户' }).click()
    await expect(page).toHaveURL(new RegExp(`${appBase}/user/account$`))
    await expect(page.locator('.profile .el-tag')).toHaveText('会员')
    await expect(page.locator('.el-message--success')).toContainText('会员已开通')
    expect(context.pages()).toHaveLength(1)
    expect(await page.evaluate(() => sessionStorage.getItem('membership-form-target'))).toBe(
      '_self',
    )
    expect(state.creates).toBe(1)
    expect(state.errors).toEqual([])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

test('wallet purchase and descriptions show the correct transaction direction', async ({
  page,
  baseURL,
}) => {
  const state = await fixture(page, baseURL, 500)
  await page.getByRole('button', { name: /余额支付.*188/ }).click()
  await expect(page.locator('.profile .el-tag')).toHaveText('会员')
  expect(state.account.balance).toBe(312)
  expect(state.forms).toBe(0)
  await page.goto(`${appBase}/user/rechargeHistory`)
  await expect(page.getByText('会员充值扣费（月度）', { exact: true })).toBeVisible()
  await expect(page.getByText('−¥188.00', { exact: true })).toBeVisible()
  await expect(page.getByText('+¥88.00', { exact: true })).toBeVisible()
  await expect(page.getByText('已扣费', { exact: true })).toBeVisible()
})

test('failed purchase preserves selection and a pending order survives refresh and cancellation', async ({
  page,
  baseURL,
}) => {
  const state = await fixture(page, baseURL)
  state.failCreate = true
  await page.getByRole('button', { name: /季度/ }).click()
  await page.getByRole('button', { name: /支付宝补差/ }).click()
  await expect(page.locator('[data-testid=operation-error-alert]')).toContainText('余额核验失败')
  await expect(page.getByRole('button', { name: /季度/ })).toHaveAttribute('aria-pressed', 'true')
  state.failCreate = false
  await page.getByRole('button', { name: /支付宝补差/ }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '继续支付' })).toBeVisible()
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  await expect(page.locator('.pending-membership')).toHaveCount(0)
  expect(state.account.reserved_balance).toBe(0)
  expect(state.account.available_balance).toBe(100)
})
