import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
const base = process.env.E2E_APP_BASE || '/'
const evidence = process.env.E2E_AI_EVIDENCE_DIR || '../../.work/ai-floating-v1/screenshots'
mkdirSync(evidence, { recursive: true })
const markdownAnswer = `## 最近7天经营情况

**总体**

- 停车场数量：1个，当前余位31个
- 累计进场3次，出场2次，营收1.0元

| 日期 | 入场 | 出场 | 营收 | 备注 |
| --- | --- | --- | --- | --- |
| 11-03 | 0 | 0 | 0元 | 无车辆进出 |
| 11-04 | 1 | 1 | 1.0元 | 测试停车场营业收入明细 |

> 统计范围：本人管理的停车场

1. 查看停车明细
2. 核对收费规则

\`\`\`text
parking_summary: revenue=1.00, entries=3, exits=2, available=31
\`\`\`

[说明](https://example.com/docs)
<script>window.markdownExecuted=true</script>
<img src="https://example.com/tracker" onerror="window.markdownExecuted=true">
[危险](javascript:alert(1))`
async function setup(page, role = -1, mode = 'normal') {
  const token = ['e30', Buffer.from(JSON.stringify({ claims: { id: 7, permissions: role }, exp: Math.floor(Date.now()/1000)+3600 })).toString('base64url'), 'fixture'].join('.')
  await page.addInitScript(t => sessionStorage.setItem('parking-token', t), token)
  let current = [], writes = 0, chats = 0, release, deleted = false, deletes = 0
  const errors = [], unexpected = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname.split('/api/')[1]
    let data
    if (path === 'user/userInfo') data = { id:7,user_name:'AI验收用户',balance:100,available_balance:100,status:0 }
    else if (path === 'user/logout' || path === 'admin/logout') data = null
    else if (path === 'admin/adminInfo') data = { id:7,permissions:role,admin_name:'AI验收管理员' }
    else if (path === 'parking/catalog') data = [{id:1,park_name:'隔离停车场',park_storey:1}]
    else if (path === 'spaces/layout') data = [{id:9,park_id:1,spaces_storey:1,spaces_row:1,spaces_col:1,spaces_name:'A01',spaces_flag:0}]
    else if (path === 'dashboard/summary') data = {counters:{},series:[],days:7}
    else if (path === 'ai/sessions') data = chats && !deleted ? [{chatId:'fixture-history',title:'停车场经营情况',createdAt:'2026-11-09T02:00:00Z',updatedAt:'2026-11-09T02:01:00Z',expired:mode!=='markdown'}] : []
    else if (path === 'ai/sessions/fixture-history' && route.request().method()==='DELETE') { deletes++; if(mode==='delete-error') return route.fulfill({status:500,json:{code:1,message:'删除失败，请重试'}});deleted=true;data=null }
    else if (path === 'ai/status') data = { enabled:true, configured:true, connectionVerified:false }
    else if (path === 'ai/history/chat') return route.fulfill({ json: chats ? ['fixture-history'] : [] })
    else if (path === 'ai/history/chat/fixture-history') return mode === 'markdown'
      ? route.fulfill({json:[{role:'assistant',content:markdownAnswer}]})
      : route.fulfill({ json: [], headers: { 'X-AI-History-Expired':'true' } })
    else if (path === 'ai/chat') {
      chats++
      if (mode === 'slow') await new Promise(resolve => { release = resolve })
      const events = mode === 'error' && chats === 1
        ? [{type:'error',message:'AI服务暂不可用，请稍后重试',requestId:'fixture-ai-request'}, {type:'done',success:false}]
        : [{type:'message_time',role:'user',timestamp:'2026-11-09T02:00:00Z'},{type:'message_time',role:'assistant',timestamp:'2026-11-09T02:01:00Z'},{type:'progress',message:'正在查询业务数据'}, {type:'delta',text:mode==='markdown'?markdownAnswer:'找到车位，点击卡片确认后才会预约。'},
          ...(role === -1 ? [{type:'action',data:{action:current.length?'cancel':'reserve',spaceId:9,parkingId:1,floor:1,spaceName:'A01',parkingName:'隔离停车场',reservationId:current.length?11:undefined}}] : []), {type:'done',success:true}]
      return route.fulfill({contentType:'text/event-stream',body:events.map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join('')})
    } else if (path === 'enter/userEnterOrReservation') data = current
    else if (path === 'spaces/availability') data = [{id:9,spaces_flag:current.length?1:0}]
    else if (path === 'enter/reservation') {
      writes++; current=[{id:11,space_id:9,flag:0,reservation:1}]
      if(mode === 'lost') return route.abort('failed')
      data=null
    } else if (path === 'enter/cancelReservation') { writes++;current=[];data=null }
    else { unexpected.push(path);return route.fulfill({status:500,json:{code:1,message:'unexpected'}}) }
    return route.fulfill({json:{code:0,data}})
  })
  await page.goto(`${base}${role===-1?'user/info':'admin/security'}`)
  await page.getByRole('button',{name:'打开系统助手-可乐',exact:true}).click()
  await expect(page.getByRole('dialog',{name:'系统助手-可乐'})).toBeVisible()
  await expect(page.getByRole('textbox',{name:'输入问题'})).toBeVisible()
  return { get deletes(){return deletes},get writes(){return writes},get chats(){return chats},finish:()=>release?.(),errors,unexpected }
}
async function send(page,text='请帮我查询空闲车位') {
  await page.getByRole('textbox',{name:'输入问题'}).fill(text)
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await expect(page.getByRole('button',{name:'发送',exact:true})).toBeVisible()
}
for (const width of [375,768,1440]) test(`markdown answers and history stay readable at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900})
  const state=await setup(page,0,'markdown')
  await send(page,'最近7天停车场的经营情况如何？')
  const content=page.locator('.ai-markdown')
  await expect(content.locator('strong')).toHaveText('总体')
  await expect(content.locator('table tbody tr')).toHaveCount(2)
  await expect(content.locator('ul li')).toHaveCount(2)
  await expect(content.locator('ol li')).toHaveCount(2)
  await expect(content.locator('pre code')).toContainText('parking_summary')
  await expect(content.locator('script,img,iframe,[onerror]')).toHaveCount(0)
  expect(await page.evaluate(()=>window.markdownExecuted)).toBeUndefined()
  await expect(content.locator('a[href]')).toHaveCount(1)
  await expect(content.locator('a[href]')).toHaveAttribute('rel','noopener noreferrer')
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  for (const tag of ['table','pre']) {
    const dimensions=await content.locator(tag).evaluate(el=>{
      el.scrollLeft=10000
      return {overflow:getComputedStyle(el).overflowX,client:el.clientWidth,scroll:el.scrollWidth,left:el.scrollLeft}
    })
    expect(dimensions.overflow).toBe('auto')
    expect(dimensions.scroll).toBeGreaterThan(dimensions.client)
    expect(dimensions.left).toBeGreaterThan(0)
    await content.locator(tag).evaluate(el=>{el.scrollLeft=0})
  }
  await page.screenshot({path:`${evidence}/markdown-${width}.png`})
  await page.getByRole('button',{name:'历史会话',exact:true}).click()
  await page.locator('.ai-history-select').click()
  await expect(content.locator('table tbody tr')).toHaveCount(2)
  expect(state.writes).toBe(0);expect(state.errors).toEqual([]);expect(state.unexpected).toEqual([])
})
for (const width of [375,768,1440]) test(`robot launcher and assistant title fit all states at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900})
  const state=await setup(page,-1,'slow')
  const title=await page.locator('#ai-window-title').boundingBox()
  const toolbar=await page.locator('.ai-toolbar').boundingBox()
  expect(title.x+title.width).toBeLessThanOrEqual(toolbar.x)
  await expect(page.locator('#ai-window-title')).toHaveText('系统助手-可乐')
  const checkLauncher=async()=>{
    const button=page.locator('.ai-launcher')
    await expect(button).toHaveText('')
    await expect(button).toHaveAttribute('translate','no')
    const b=await button.boundingBox(), icon=await button.locator('svg').boundingBox()
    expect(b.width).toBe(56);expect(b.height).toBe(56)
    expect(Math.abs(icon.x+icon.width/2-b.x-b.width/2)).toBeLessThan(1)
    expect(Math.abs(icon.y+icon.height/2-b.y-b.height/2)).toBeLessThan(1)
    expect(b.x+b.width).toBeLessThanOrEqual(width)
  }
  await checkLauncher()
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await checkLauncher()
  await page.screenshot({path:`${evidence}/robot-idle-${width}.png`})
  await page.locator('.ai-launcher').click()
  await page.getByRole('textbox',{name:'输入问题'}).fill('查询车位')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await expect.poll(()=>state.chats).toBe(1)
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await expect(page.locator('.ai-floating-status')).toHaveText('正在回答…')
  await checkLauncher()
  await page.screenshot({path:`${evidence}/robot-working-${width}.png`})
  state.finish()
  await expect(page.locator('.ai-unread')).toBeVisible()
  await checkLauncher()
  await page.screenshot({path:`${evidence}/robot-unread-${width}.png`})
  await page.locator('.ai-launcher').click()
  await expect(page.locator('.ai-unread')).toHaveCount(0)
  expect(state.errors).toEqual([])
})
for(const width of [375,768,1440]) test(`AI user confirms reserve and cancel at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900});const state=await setup(page)
  await send(page)
  await expect(page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true})).toBeEnabled()
  expect(state.writes).toBe(0)
  await page.screenshot({path:`${evidence}/user-${width}.png`,fullPage:true})
  await page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true}).click()
  await expect(page.locator('.ai-action [role=status]')).toHaveText('预约成功')
  expect(state.writes).toBe(1)
  await send(page,'取消我的预约')
  await page.getByRole('button',{name:'确认取消',exact:true}).click()
  await expect(page.locator('.ai-action [role=status]').last()).toHaveText('已取消预约')
  expect(state.writes).toBe(2)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  expect(state.errors).toEqual([]);expect(state.unexpected).toEqual([])
})
test('AI admin read only and expired history',async({page})=>{
  const state=await setup(page,0);await send(page,'查询经营情况')
  await expect(page.locator('.ai-action')).toHaveCount(0)
  await page.getByRole('button',{name:'历史会话',exact:true}).click()
  await page.locator('.ai-history-select').click()
  await expect(page.getByText('此会话内容已过期或服务已重启，请重新提问')).toBeVisible()
  expect(state.writes).toBe(0);expect(state.errors).toEqual([])
})
test('AI stream error preserves draft and supports retry',async({page})=>{
  const state=await setup(page,-1,'error');await send(page,'重试问题')
  await expect(page.getByRole('textbox',{name:'输入问题'})).toHaveValue('重试问题')
  await page.getByRole('button',{name:'重新提问',exact:true}).click()
  await expect(page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true})).toBeEnabled()
  expect(state.writes).toBe(0);expect(state.errors).toEqual([])
})
test('AI lost reservation response is reconciled without second write',async({page})=>{
  const state=await setup(page,-1,'lost');await send(page)
  await page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true}).click()
  await expect(page.locator('.ai-action [role=status]')).toHaveText('已确认预约成功')
  expect(state.writes).toBe(1)
})

test('floating conversation continues across navigation while collapsed',async({page})=>{
  const state=await setup(page,-1,'slow')
  await expect(page.locator('nav').getByText('AI 助手')).toHaveCount(0)
  await page.getByRole('textbox',{name:'输入问题'}).fill('保持会话的问题')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await expect.poll(()=>state.chats).toBe(1)
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await page.getByRole('link',{name:/车位预约/}).click()
  await expect(page.locator('canvas')).toBeVisible()
  await expect(page.getByRole('button',{name:'打开系统助手-可乐，正在回答'})).toBeVisible()
  state.finish()
  await expect(page.getByRole('button',{name:'打开系统助手-可乐，有新消息'})).toBeVisible()
  await page.getByRole('button',{name:'打开系统助手-可乐，有新消息'}).click()
  await expect(page.locator('.ai-message.user')).toContainText('保持会话的问题')
  await expect(page.locator('.ai-action')).toBeVisible()
  await page.getByRole('textbox',{name:'输入问题'}).fill('未发送草稿')
  await page.keyboard.press('Escape')
  await expect(page.locator('.ai-launcher')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('textbox',{name:'输入问题'})).toHaveValue('未发送草稿')
  await page.getByRole('button',{name:'新建会话'}).click()
  await expect(page.locator('.ai-message')).toHaveCount(0)
  expect(state.errors).toEqual([]);expect(state.unexpected).toEqual([])
})
test('legacy links open the floating window and confirmations refresh the visible map',async({page})=>{
  const state=await setup(page)
  await page.goto(`${base}user/ai`)
  await expect(page).toHaveURL(new RegExp('/user/spaceReservation$'))
  await expect(page.getByRole('dialog',{name:'系统助手-可乐'})).toBeVisible()
  await expect(page.locator('.free-count')).toHaveText('1')
  await send(page)
  await page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true}).click()
  await expect(page.locator('.free-count')).toHaveText('0')
  await send(page,'取消预约')
  await page.locator('.ai-action').getByRole('button',{name:'确认取消',exact:true}).click()
  await expect(page.locator('.free-count')).toHaveText('1')
  expect(state.writes).toBe(2);expect(state.errors).toEqual([])
})
test('floating window follows native fullscreen without restarting chat',async({page})=>{
  const state=await setup(page)
  await page.getByRole('link',{name:/车位预约/}).click()
  await page.getByRole('textbox',{name:'输入问题'}).fill('全屏草稿')
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await page.getByRole('button',{name:'全屏地图',exact:true}).click()
  await page.getByRole('button',{name:'打开系统助手-可乐',exact:true}).click()
  await expect.poll(()=>page.evaluate(()=>!!document.fullscreenElement)).toBe(true)
  await expect(page.locator('.parking-map .ai-floating')).toHaveCount(1)
  await expect(page.getByRole('textbox',{name:'输入问题'})).toHaveValue('全屏草稿')
  await page.evaluate(()=>document.exitFullscreen())
  await expect(page.locator('body > .ai-floating')).toHaveCount(1)
  await expect(page.getByRole('textbox',{name:'输入问题'})).toHaveValue('全屏草稿')
  expect(state.errors).toEqual([])
})
test('floating window remains usable in fallback fullscreen and constrained viewport',async({page})=>{
  const state=await setup(page)
  await page.getByRole('link',{name:/车位预约/}).click()
  await page.evaluate(()=>{Element.prototype.requestFullscreen=()=>Promise.reject(new Error('fixture'))})
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await page.getByRole('button',{name:'全屏地图',exact:true}).click()
  await page.getByRole('button',{name:'打开系统助手-可乐',exact:true}).click()
  await expect(page.locator('.parking-map.map-full')).toBeVisible()
  await page.setViewportSize({width:375,height:460})
  const box=await page.getByRole('dialog',{name:'系统助手-可乐'}).boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(0);expect(box.y).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(375);expect(box.y+box.height).toBeLessThan(460)
  await send(page)
  await expect(page.locator('.ai-action').getByRole('button',{name:'确认预约',exact:true})).toBeVisible()
  expect(state.errors).toEqual([])
})
test('logout removes floating session and login page has no launcher',async({page})=>{
  await setup(page)
  await send(page)
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await page.getByRole('button',{name:'退出',exact:true}).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('.ai-floating')).toHaveCount(0)
})

for (const width of [375,768,1440]) test(`compact chat resizes and preserves dimensions at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900});await setup(page)
  await send(page)
  await expect(page.locator('.ai-description,.ai-empty,.ai-message > strong')).toHaveCount(0)
  await expect(page.locator('.ai-message-time').first()).toHaveText('10:00')
  const user=await page.locator('.ai-message.user').boundingBox(), assistant=await page.locator('.ai-message.assistant').boundingBox()
  expect(user.x).toBeGreaterThan(assistant.x)
  const dialog=page.locator('.ai-window')
  await page.getByRole('button',{name:'调整聊天窗宽度',exact:true}).focus()
  await page.keyboard.press('ArrowLeft')
  const handle=await page.getByRole('button',{name:'调整聊天窗大小',exact:true}).boundingBox()
  await page.mouse.move(handle.x+9,handle.y+9);await page.mouse.down();await page.mouse.move(0,0,{steps:8});await page.mouse.up()
  const box=await dialog.boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(10);expect(box.y).toBeGreaterThanOrEqual(10)
  expect(box.x+box.width).toBeLessThanOrEqual(width-10)
  await page.locator('.ai-header').getByRole('button',{name:'收起系统助手-可乐'}).click()
  if(width < 768) await page.getByRole('button',{name:'打开菜单',exact:true}).click()
  await page.getByRole('link',{name:/车位预约/}).click()
  await page.locator('.ai-launcher').click()
  const restored=await dialog.boundingBox();expect(restored.width).toBe(box.width);expect(restored.height).toBe(box.height)
  await page.screenshot({path:`${evidence}/compact-resized-${width}.png`})
})
test('history deletes current session with confirmation and no resurrection',async({page})=>{
  const state=await setup(page,-1,'markdown');await send(page)
  await page.getByRole('button',{name:'历史会话',exact:true}).click()
  await page.locator('.ai-history-select').click()
  await expect(page.locator('.ai-markdown table')).toBeVisible()
  await page.getByRole('button',{name:'历史会话',exact:true}).click()
  await page.getByRole('button',{name:'删除会话：停车场经营情况'}).click()
  expect(state.deletes).toBe(0)
  await page.getByRole('button',{name:'删除',exact:true}).click()
  await expect(page.getByText('暂无会话', {exact:true})).toBeVisible()
  await expect(page.locator('.ai-message')).toHaveCount(0)
  expect(state.deletes).toBe(1)
})
test('failed history deletion keeps the row and conversation',async({page})=>{
  const state=await setup(page,-1,'delete-error');await send(page)
  await page.getByRole('button',{name:'历史会话',exact:true}).click()
  await page.getByRole('button',{name:'删除会话：停车场经营情况'}).click()
  await page.getByRole('button',{name:'删除',exact:true}).click()
  await expect(page.locator('.ai-history-select')).toBeVisible()
  await expect(page.locator('.ai-message.user')).toBeVisible()
  await expect(page.getByText('删除失败，请重试',{exact:true})).toBeVisible()
  expect(state.deletes).toBe(1)
})
