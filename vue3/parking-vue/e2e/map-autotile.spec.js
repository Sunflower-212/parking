import { test, expect } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { CELL, fitView } from '../src/utils/map-geometry.js'

const appBase = process.env.E2E_APP_BASE || '/'
const evidence = resolve(process.env.E2E_MAP_EVIDENCE_DIR || '../../.work/autotile-verify')
mkdirSync(evidence, { recursive: true })
const directions = [
  [-1, 0],
  [0, 1],
  [1, 0],
  [0, -1],
]
const makeCell = (row, col, flag) => ({
  id: row * 100 + col,
  spaces_row: row,
  spaces_col: col,
  spaces_flag: flag,
  spaces_name: `P${row}-${col}`,
  spaces_storey: 1,
  park_id: 1,
  editable: true,
})
function gallery(type) {
  const cells = []
  for (let mask = 0; mask < 16; mask++) {
    const row = Math.floor(mask / 4) * 4 + 2,
      col = (mask % 4) * 4 + 2
    cells.push(makeCell(row, col, type))
    directions.forEach(([dr, dc], bit) => {
      if (mask & (1 << bit)) cells.push(makeCell(row + dr, col + dc, type))
    })
  }
  return cells
}
function parking() {
  const cells = []
  for (let row = 1; row <= 9; row++)
    for (let col = 1; col <= 17; col++) {
      if (row === 1 || row === 9 || col === 1 || col === 17)
        cells.push(makeCell(row, col, row === 1 && col === 9 ? 4 : 3))
      else if (row === 2 || row === 8 || [3, 6, 9, 12, 15].includes(col))
        cells.push(makeCell(row, col, 2))
      else cells.push(makeCell(row, col, 0))
    }
  return cells
}
async function setup(page, initial, role = 1) {
  let snapshot = { revision: 'fixture-1', items: initial.map((c) => ({ ...c })) }
  let saves = 0
  const unexpected = [],
    errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  const jwt = [
    'e30',
    Buffer.from(
      JSON.stringify({
        claims: { id: 7, permissions: role },
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    ).toString('base64url'),
    'fixture-only',
  ].join('.')
  await page.addInitScript((token) => sessionStorage.setItem('parking-token', token), jwt)
  // 所有 API 都拦截在浏览器内，禁止隔离地图触碰日常数据库。
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname.split('/api/')[1]
    let data
    if (path === 'admin/adminInfo') data = { id: 7, permissions: role, admin_name: '地图验收' }
    else if (path === 'user/userInfo') data = { id: 7, status: 0, user_name: '地图验收用户' }
    else if (path === 'parking/catalog')
      data = [{ id: 1, park_name: '自动拼接验收', park_storey: 1 }]
    else if (path === 'spaces/editor') data = snapshot
    else if (['spaces/layout', 'spaces/availability'].includes(path)) data = snapshot.items
    else if (path === 'enter/userEnterOrReservation') data = []
    else if (path === 'spaces/layout/batch') {
      const body = route.request().postDataJSON()
      expect(body.baseRevision).toBe(snapshot.revision)
      saves++
      let items = snapshot.items.filter((c) => !body.deleteIds.includes(c.id))
      items = items.map((c) => {
        const update = body.updates.find((u) => u.id === c.id)
        return update ? { ...c, spaces_name: update.name, spaces_flag: update.type } : c
      })
      for (const c of body.creates)
        items.push({ ...makeCell(c.row, c.col, c.type), spaces_name: c.name })
      snapshot = { revision: `fixture-${saves + 1}`, items }
      data = snapshot
    } else {
      unexpected.push(path)
      return route.fulfill({ status: 500, json: { code: 1, message: '隔离测试未配置的请求' } })
    }
    return route.fulfill({ json: { code: 0, data } })
  })
  const path = role === -1 ? 'user/spaceReservation' : 'admin/spaceManagement'
  await page.goto(`${appBase}${path}`)
  await expect(page.locator('canvas')).toBeVisible()
  await expect(page.getByRole('cell', { name: initial[0].spaces_name, exact: true })).toBeVisible()
  return {
    get snapshot() {
      return snapshot
    },
    get saves() {
      return saves
    },
    unexpected,
    errors,
  }
}
async function fit(page) {
  await page.getByRole('button', { name: '适配全图', exact: true }).click()
  await page.evaluate(
    () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
  )
}
async function point(page, cells, row, col) {
  const bounds = await page.locator('canvas').boundingBox()
  const view = fitView(cells, bounds.width, bounds.height)
  return {
    x: view.offset.x + (col - 0.5) * CELL * view.scale,
    y: view.offset.y + (row - 0.5) * CELL * view.scale,
  }
}
async function measure(page, name) {
  const samples = await page.locator('canvas').evaluate(async (canvas) => {
    const rect = canvas.getBoundingClientRect(),
      times = []
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
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
      times.push(performance.now() - start)
    }
    return times
  })
  const sorted = [...samples].sort((a, b) => a - b),
    p95 = sorted[Math.ceil(samples.length * 0.95) - 1]
  writeFileSync(
    resolve(evidence, `${name}-performance.json`),
    JSON.stringify(
      {
        name,
        samples,
        p95,
        viewport: page.viewportSize(),
        browser: 'headless Chrome',
        measurement: 'synthetic wheel dispatch to two animation frames, not physical input latency',
      },
      null,
      2,
    ),
  )
  expect(p95).toBeLessThanOrEqual(100)
}

for (const [label, type] of [
  ['roads', 2],
  ['walls', 3],
]) {
  test(`all 16 ${label} connection shapes in the production canvas`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    const fixture = await setup(page, gallery(type))
    await page.getByRole('button', { name: '全屏地图', exact: true }).click()
    await fit(page)
    // 验证每个格子边缘：有连接时到达边缘，不能只凭截图认定绘制正确。
    const pixels = await page.locator('canvas').evaluate(
      (canvas, { cells, size }) => {
        const width = canvas.clientWidth,
          height = canvas.clientHeight
        const maxCol = Math.max(4, ...cells.map((c) => c.spaces_col))
        const maxRow = Math.max(3, ...cells.map((c) => c.spaces_row))
        const scale = Math.max(
          0.02,
          Math.min(1.3, (width - 48) / (maxCol * size), (height - 48) / (maxRow * size)),
        )
        const ox = (width - maxCol * size * scale) / 2,
          oy = (height - maxRow * size * scale) / 2
        const dpr = canvas.width / width,
          ctx = canvas.getContext('2d'),
          result = []
        for (let mask = 0; mask < 16; mask++) {
          const row = Math.floor(mask / 4) * 4 + 2,
            col = (mask % 4) * 4 + 2
          for (const [dr, dc] of [
            [-0.42, 0],
            [0, 0.42],
            [0.42, 0],
            [0, -0.42],
          ]) {
            const x = (ox + (col - 0.5 + dc) * size * scale) * dpr
            const y = (oy + (row - 0.5 + dr) * size * scale) * dpr
            result.push([...ctx.getImageData(Math.round(x), Math.round(y), 1, 1).data])
          }
        }
        return result
      },
      { cells: fixture.snapshot.items, size: CELL },
    )
    if (type === 3)
      for (let mask = 0; mask < 16; mask++)
        for (let bit = 0; bit < 4; bit++)
          expect(pixels[mask * 4 + bit].slice(0, 3)).toEqual(
            mask & (1 << bit) ? [71, 85, 105] : [243, 246, 250],
          )
    await page
      .locator('.parking-map')
      .screenshot({ path: resolve(evidence, `${label}-16-shapes.png`) })
    expect(fixture.errors).toEqual([])
    expect(fixture.unexpected).toEqual([])
  })
}

for (const width of [375, 768, 1440]) {
  test(`parking perimeter, T junctions and entrance at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    const fixture = await setup(page, parking())
    await page.getByRole('button', { name: '编辑地图', exact: true }).click()
    await fit(page)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: resolve(evidence, `parking-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: '全屏地图', exact: true }).click()
    await fit(page)
    await page
      .locator('.parking-map')
      .screenshot({ path: resolve(evidence, `parking-fullscreen-${width}.png`) })
    await page.getByRole('button', { name: '放大地图', exact: true }).click()
    const bounds = await page.locator('canvas').boundingBox()
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    await page.mouse.down()
    await page.mouse.move(bounds.x + bounds.width / 2 + 30, bounds.y + bounds.height / 2 + 20)
    await page.mouse.up()
    await page.getByRole('button', { name: '退出全屏', exact: true }).click()
    expect(fixture.errors).toEqual([])
  })
}

test('editing a branch, undo/redo, saving and reloading preserve adaptive connections', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const cells = [makeCell(3, 2, 2), makeCell(3, 3, 2), makeCell(3, 4, 2), makeCell(5, 5, 0)]
  const fixture = await setup(page, cells)
  await page.getByRole('button', { name: '编辑地图', exact: true }).click()
  await page.getByRole('button', { name: '═ 道路', exact: true }).click()
  await fit(page)
  const branch = await point(page, cells, 4, 3)
  await page.locator('canvas').click({ position: branch })
  await expect(page.getByRole('cell', { name: '道4-3', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.getByRole('cell', { name: '道4-3', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(page.getByRole('cell', { name: '道4-3', exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(evidence, 'branch-draft.png'), fullPage: true })
  await page.getByRole('button', { name: '预览并保存', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(page.getByText('地图已整批保存', { exact: true })).toBeVisible()
  expect(fixture.saves).toBe(1)
  expect(
    fixture.snapshot.items.find((c) => c.spaces_row === 4 && c.spaces_col === 3).spaces_flag,
  ).toBe(2)
  await page.reload()
  await expect(page.getByRole('cell', { name: '道4-3', exact: true })).toBeVisible()
  expect(fixture.errors).toEqual([])
  expect(fixture.unexpected).toEqual([])
})

test('user selection remains available on the shared map', async ({ page }) => {
  const fixture = await setup(page, parking(), -1)
  await page.getByPlaceholder('搜索位置名称').fill('P3-2')
  await page
    .getByRole('row')
    .filter({ hasText: 'P3-2' })
    .getByRole('button', { name: '选择', exact: true })
    .click()
  await expect(page.locator('.space-inspector h2')).toHaveText('P3-2')
  await expect(page.getByRole('button', { name: '确认预约', exact: true })).toBeEnabled()
  await page.locator('.parking-map').screenshot({ path: resolve(evidence, 'user-map.png') })
  expect(fixture.unexpected).toEqual([])
  expect(fixture.errors).toEqual([])
})

test('ordinary administrator keeps the same editing tools and adaptive map', async ({ page }) => {
  const fixture = await setup(page, parking(), 0)
  await page.getByRole('button', { name: '编辑地图', exact: true }).click()
  await expect(page.getByRole('button', { name: '═ 道路', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '▥ 墙体', exact: true })).toBeEnabled()
  expect(fixture.errors).toEqual([])
  expect(fixture.unexpected).toEqual([])
})

test('batch generation works without native randomUUID on HTTP', async ({ page }) => {
  await page.addInitScript(() =>
    Object.defineProperty(Crypto.prototype, 'randomUUID', { value: undefined }),
  )
  const fixture = await setup(page, [makeCell(5, 5, 0)])
  await page.getByRole('button', { name: '编辑地图', exact: true }).click()
  await page.getByRole('button', { name: '批量生成车位', exact: true }).click()
  await page.getByRole('button', { name: '生成预览', exact: true }).click()
  await expect(page.getByText('预览 10 个车位', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '加入草稿', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'A001', exact: true })).toBeVisible()
  expect(fixture.errors).toEqual([])
})

for (const count of [1000, 10000]) {
  test(`${count} positions: bounded rendering, wheel and touch interactions`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    const cells = Array.from({ length: count }, (_, i) => {
      const row = Math.floor(i / 100) + 1,
        col = (i % 100) + 1
      return makeCell(row, col, row % 5 === 0 || col % 5 === 0 ? 2 : row % 11 === 0 ? 3 : 0)
    })
    const fixture = await setup(page, cells)
    await page.getByRole('button', { name: '全屏地图', exact: true }).click()
    await fit(page)
    await measure(page, `map-${count}-fit`)
    for (let i = 0; i < 8; i++)
      await page.getByRole('button', { name: '放大地图', exact: true }).click()
    await measure(page, `map-${count}-detail`)
    const session = await page.context().newCDPSession(page)
    await session.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
    const bounds = await page.locator('canvas').boundingBox(),
      x = bounds.x + bounds.width / 2,
      y = bounds.y + bounds.height / 2
    const before = await page.locator('.map-controls > span').innerText()
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
    await expect(page.locator('.map-controls > span')).not.toHaveText(before)
    await session.send('Emulation.setTouchEmulationEnabled', { enabled: false })
    await page.locator('.parking-map').screenshot({ path: resolve(evidence, `map-${count}.png`) })
    expect(fixture.errors).toEqual([])
    expect(fixture.unexpected).toEqual([])
  })
}
