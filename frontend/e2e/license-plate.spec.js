import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
const base = process.env.E2E_APP_BASE || '/'
const evidence = process.env.E2E_PLATE_EVIDENCE_DIR || '../.work/plate-input-v1/screenshots'
mkdirSync(evidence, { recursive: true })
async function setup(page, plate = '皖H-88888', admin = false) {
  const token = [
    'e30',
    Buffer.from(
      JSON.stringify({
        claims: { id: 7, permissions: admin ? 1 : -1 },
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ).toString('base64url'),
    'fixture',
  ].join('.')
  await page.addInitScript((t) => sessionStorage.setItem('parking-token', t), token)
  let saved = [],
    fail = true
  const info = {
    id: 7,
    user_name: '车牌验收',
    name: '测试',
    plate_num: plate,
    car_color: '白色',
    tel_num: '13912345678',
    status: 0,
  }
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.split('/api/')[1]
    if (path === 'user/update') {
      saved.push(route.request().postDataJSON())
      if (fail) {
        fail = false
        return route.fulfill({ status: 503, json: { code: 1, message: '服务暂不可用，请重试' } })
      }
      Object.assign(info, saved.at(-1))
      return route.fulfill({ json: { code: 0 } })
    }
    const data =
      path === 'user/userInfo'
        ? info
        : path === 'admin/adminInfo'
          ? { id: 7, permissions: 1, admin_name: '车牌管理员' }
          : path === 'user/pageList'
            ? { total: 0, items: [] }
            : null
    await route.fulfill({ json: { code: 0, data } })
  })
  await page.goto(base + (admin ? 'admin/userManagement' : 'user/info'))
  return { saved, errors }
}
for (const width of [375, 768, 1440])
  test(`click plate keyboard and save preserves input at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const state = await setup(page)
    await expect(page.locator('[data-cell="0"]')).toHaveText('皖')
    await page.locator('[data-cell="0"]').focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', { name: '输入京', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '输入京', exact: true }).click()
    await expect(page.locator('.plate-input input:not([readonly])')).toHaveCount(0)
    for (const [label, tail] of [
      ['小型新能源', 'GA1234'],
      ['大型新能源', '12345H'],
    ]) {
      await page.locator('.plate-type-label .el-select').click()
      await page.getByRole('option', { name: label, exact: true }).click()
      await page.getByRole('button', { name: '继续', exact: true }).click()
      await expect(page.locator('[data-cell]')).toHaveCount(8)
      for (const c of tail)
        await page.getByRole('button', { name: '输入' + c, exact: true }).click()
      await expect(page.locator('.el-form-item__error')).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      )
      await page.screenshot({ path: `${evidence}/plate-${label}-${width}.png`, fullPage: true })
    }
    await page.getByRole('button', { name: '完成', exact: true }).click()
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await expect(page.locator('[data-cell="7"]')).toHaveText('H')
    await expect.poll(() => state.saved.length).toBe(1)
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await expect.poll(() => state.saved.length).toBe(2)
    expect(state.saved[0].plate_num).toBe('京H-12345H')
    expect(state.saved[1].plate_num).toBe('京H-12345H')
    expect(state.errors).toEqual([])
  })
test('unsupported history remains visible and incomplete plate cannot save', async ({ page }) => {
  const state = await setup(page, '历史号牌-123456789')
  await expect(page.locator('.plate-legacy')).toContainText('历史号牌-123456789')
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(page.locator('.plate-keyboard')).toBeVisible()
  await expect(page.locator('[data-cell="0"]')).toBeFocused()
  expect(state.saved).toHaveLength(0)
  await expect(page.locator('.plate-legacy')).toContainText('历史号牌-123456789')
})
