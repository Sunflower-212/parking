import { test, expect } from '@playwright/test'
import { writeFileSync } from 'node:fs'

for (const count of [1000, 10000]) {
  test(`map ${count} positions: bounded rendering, pointer zoom and responsive layout`, async ({
    page,
  }) => {
    const cells = Array.from({ length: count }, (_, i) => ({
      id: i + 1,
      park_id: 1,
      spaces_name: `P${i + 1}`,
      spaces_row: Math.floor(i / 100) + 1,
      spaces_col: (i % 100) + 1,
      spaces_storey: 1,
      spaces_flag: i % 7 === 0 ? 2 : 0,
      editable: true,
    }))
    await page.route('**/api/parking/catalog', (route) =>
      route.fulfill({
        json: { code: 0, data: [{ id: 1, park_name: '隔离大地图', park_storey: 2 }] },
      }),
    )
    await page.route('**/api/spaces/editor?*', (route) =>
      route.fulfill({ json: { code: 0, data: { revision: 'fixture-v1', items: cells } } }),
    )
    await page.goto('/login')
    await page
      .getByPlaceholder('请输入账号', { exact: true })
      .fill(process.env.BOOTSTRAP_ADMIN_USERNAME)
    await page
      .getByPlaceholder('请输入密码', { exact: true })
      .fill(process.env.BOOTSTRAP_ADMIN_PASSWORD)
    await page.getByText('管理员登录', { exact: true }).click()
    await page.getByRole('button', { name: /^登\s*录$/ }).click()
    await expect(page).toHaveURL(/\/admin\//)
    await page.goto('/admin/spaceManagement')
    await expect(page.getByRole('cell', { name: 'P1', exact: true })).toBeVisible()
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.getByRole('button', { name: '适配全图', exact: true }).click()
    const session = await page.context().newCDPSession(page)
    await session.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
    const touchBounds = await page.locator('canvas').boundingBox(),
      x = touchBounds.x + touchBounds.width / 2,
      y = touchBounds.y + touchBounds.height / 2
    const priorZoom = await page.locator('.map-controls > span').innerText()
    await session.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [
        { x: x - 30, y, id: 1 },
        { x: x + 30, y, id: 2 },
      ],
    })
    await session.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [
        { x: x - 60, y: y + 15, id: 1 },
        { x: x + 60, y: y + 15, id: 2 },
      ],
    })
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await expect(page.locator('.map-controls > span')).not.toHaveText(priorZoom)
    await session.send('Emulation.setTouchEmulationEnabled', { enabled: false })
    await page.getByRole('button', { name: '适配全图', exact: true }).click()
    const samples = await page.locator('canvas').evaluate(async (canvas) => {
      const rect = canvas.getBoundingClientRect(),
        times = []
      // Measure browser input dispatch to two animation frames, including the scheduled Canvas draw.
      for (let i = 0; i < 60; i++) {
        const start = performance.now()
        canvas.dispatchEvent(
          new WheelEvent('wheel', {
            clientX: rect.left + rect.width / 2,
            clientY: rect.top + rect.height / 2,
            deltaY: i % 2 ? 100 : -100,
            bubbles: true,
            cancelable: true,
          }),
        )
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
        times.push(performance.now() - start)
      }
      return times
    })
    const sorted = [...samples].sort((a, b) => a - b),
      p95 = sorted[Math.ceil(sorted.length * 0.95) - 1]
    writeFileSync(
      `../../.work/map-${count}-performance.json`,
      JSON.stringify(
        {
          count,
          viewport: [1440, 1000],
          samples,
          p95,
          measurement: 'synthetic wheel dispatch to two animation frames; headless Chromium',
        },
        null,
        2,
      ),
    )
    expect(p95).toBeLessThanOrEqual(100)
    await page.getByRole('button', { name: '编辑地图', exact: true }).click()
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.getByRole('button', { name: '适配全图', exact: true }).click()
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        .toBe(true)
      await page.screenshot({ path: `../../.work/ui/map-${count}-${width}.png`, fullPage: true })
    }
    await page.getByPlaceholder('搜索位置名称').fill(`P${count}`)
    await page.getByRole('button', { name: '选择', exact: true }).last().click()
    await expect(page.locator('.space-inspector h2')).toHaveText(`P${count}`)
    const bounds = await page.locator('canvas').boundingBox()
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    await page.mouse.wheel(0, -200)
    await page.locator('canvas').click({ position: { x: bounds.width / 2, y: bounds.height / 2 } })
    await expect(page.locator('.selection-actions strong')).toHaveText('0')
  })
}
