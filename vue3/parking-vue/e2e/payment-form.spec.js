import { test, expect } from '@playwright/test'

test('default whole-yuan recharge passes browser validation and creates one sandbox form', async ({ page, request }) => {
  const username = 'pay' + Date.now().toString().slice(-9)
  const password = 'TestPayment123'
  const registration = await request.post('/api/user/register', { form: { username, password } })
  expect((await registration.json()).code).toBe(0)
  await page.route('**/api/system/capabilities', route => route.fulfill({ json: { code: 0, data: { alipay: true } } }))
  let submits = 0
  await page.route('**/api/user/recharge', route => {
    submits++
    expect(new URLSearchParams(route.request().postData()).get('amounts')).toBe('100')
    return route.fulfill({ json: { code: 0, data: {
      rechargeId: 1, reAmounts: 100,
      form: '<form action="https://openapi-sandbox.dl.alipaydev.com/gateway.do" method="post"></form>',
    } } })
  })
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(username)
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登 录', exact: true }).click()
  await page.waitForURL('**/user/spaceReservation')
  await page.getByRole('link', { name: '个人账户' }).click()
  const amount = page.getByRole('spinbutton', { name: '充值金额 / 元' })
  await expect(amount).toHaveValue('100.00')
  expect(await amount.evaluate(input => input.checkValidity())).toBe(true)
  await page.getByRole('button', { name: '支付宝充值', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '支付宝沙箱支付' })).toBeVisible()
  expect(submits).toBe(1)
})
