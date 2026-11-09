import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'

const appBase = process.env.E2E_APP_BASE || ''
const avatarImage = readFileSync(new URL('../src/assets/default.png', import.meta.url))

for (const width of [375, 768, 1440]) {
  test(`header avatar upload, fallback and reload at ${width}px`, async ({
    page,
    baseURL,
  }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const origin = new URL(baseURL).origin
    const account = {
      id: 1,
      user_name: '较长的用户昵称用于验证顶部布局不会溢出'.repeat(3),
      status: 1,
      user_pic: null,
    }
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.addInitScript(() => {
      sessionStorage.setItem(
        'parking-token',
        `e30.${btoa(JSON.stringify({ claims: { id: 1, permissions: -1 }, exp: 4102444800 }))}.test`,
      )
    })
    await page.route('**/*', async (route) => {
      const url = new URL(route.request().url())
      if (url.origin !== origin) return route.abort()
      if (url.pathname.endsWith('/test-upload.png'))
        return route.fulfill({ contentType: 'image/png', body: avatarImage })
      if (url.pathname.endsWith('/broken-avatar.png'))
        return route.fulfill({ status: 404, body: '' })
      if (!url.pathname.includes('/api/')) return route.continue()
      const path = url.pathname.slice(url.pathname.indexOf('/api/') + 4)
      let data
      if (path === '/user/userInfo') data = account
      else if (path === '/system/capabilities') data = { oss: true }
      else if (path === '/user/upload') data = `${origin}${appBase}/test-upload.png`
      else if (path === '/user/updateAvatar') {
        account.user_pic = new URLSearchParams(route.request().postData()).get('avatarUrl')
        data = null
      } else return route.fulfill({ status: 404, json: { code: 1, message: '未配置的验收接口' } })
      return route.fulfill({ json: { code: 0, data } })
    })
    await page.goto(`${appBase}/user/avatar`)
    const avatar = page.locator('.profile-avatar')
    await expect(avatar).toBeVisible()
    const fallback = await avatar.getAttribute('src')
    await expect
      .poll(() => avatar.evaluate((img) => img.complete && img.naturalWidth > 0))
      .toBe(true)
    account.user_pic = `${origin}${appBase}/broken-avatar.png`
    await page.reload()
    await expect(avatar).toHaveAttribute('src', fallback)
    await page
      .locator('input[type=file]')
      .setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: avatarImage })
    await page.getByRole('button', { name: '上传并保存' }).click()
    await expect(page.locator('.el-message--success')).toContainText('头像已更新')
    await expect(avatar).toHaveAttribute('src', `${origin}${appBase}/test-upload.png`)
    await expect
      .poll(() => avatar.evaluate((img) => img.complete && img.naturalWidth > 0))
      .toBe(true)
    await page.reload()
    await expect(avatar).toHaveAttribute('src', `${origin}${appBase}/test-upload.png`)
    await expect(avatar).toBeVisible()
    const box = await avatar.boundingBox()
    expect(box.width).toBe(32)
    expect(box.height).toBe(32)
    expect(await avatar.evaluate((img) => getComputedStyle(img).objectFit)).toBe('cover')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const profileBox = await page.locator('.profile').boundingBox()
    expect(profileBox.x + profileBox.width).toBeLessThanOrEqual(width)
    await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
    await page.screenshot({
      path: info.outputPath(`avatar-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    })
    expect(errors).toEqual([])
  })
}
