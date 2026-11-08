import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import AiFloatingAssistant from '../src/components/AiFloatingAssistant.vue'
import { useTokenStore } from '../src/stores/token'
const mock = vi.hoisted(() => ({ get: vi.fn(), chat: vi.fn(), history: vi.fn(), sessions: vi.fn(), delete: vi.fn(), current: vi.fn(), reserve: vi.fn(), cancel: vi.fn(), success: vi.fn(), failure: vi.fn() }))
vi.mock('../src/utils/request', () => ({ default: { get: mock.get } }))
vi.mock('../src/api/ai', () => ({ aiChat: mock.chat, aiHistory: mock.history, aiSessions: mock.sessions, deleteAiSession: mock.delete }))
vi.mock('../src/api/enter', () => ({ isEnterOrReservationService: mock.current, reservationService: mock.reserve, cancelReservationService: mock.cancel }))
vi.mock('../src/utils/request-error-notice', () => ({ feedback: { success: mock.success }, localFailure: mock.failure }))
let wrapper, router, pending, store
beforeEach(async () => {
  vi.resetAllMocks()
  mock.current.mockResolvedValue({ data: [] })
  mock.reserve.mockResolvedValue({ code: 0 })
  mock.cancel.mockResolvedValue({ code: 0 })
  mock.get.mockResolvedValue({ data: { enabled: true, configured: true } })
  mock.history.mockResolvedValue({ items: [], expired: false })
  mock.sessions.mockResolvedValue({ data: [] })
  mock.delete.mockResolvedValue({code:0})
  mock.chat.mockImplementation(
    (input, signal, onEvent) =>
      new Promise((resolve, reject) => {
        pending = { signal, onEvent, resolve, reject, input }
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      }),
  )
  const pinia = createPinia()
  setActivePinia(pinia)
  store = useTokenStore()
  store.setToken('fixture-only')
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/user/info', component: { template: '<div />' } },
      { path: '/user/account', component: { template: '<div />' } },
    ],
  })
  await router.push('/user/info')
  wrapper = mount(AiFloatingAssistant, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: null })
})
async function open() {
  document.querySelector('.ai-launcher').click()
  await flushPromises()
}
async function send() {
  const input = document.querySelector('textarea')
  input.value = '查询车位'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  document
    .querySelector('form')
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}
it('uses a text-free robot launcher and a consistent untranslated assistant name', async () => {
  const button = document.querySelector('.ai-launcher')
  expect(button.textContent.trim()).toBe('')
  expect(button.querySelector('svg.ai-robot')).not.toBeNull()
  expect(button.getAttribute('translate')).toBe('no')
  expect(button.getAttribute('title')).toBe('系统助手-可乐')
  expect(button.getAttribute('aria-label')).toBe('打开系统助手-可乐')
  await open()
  expect(document.querySelector('#ai-window-title').textContent).toContain('系统助手-可乐')
  expect(document.querySelector('#ai-window-title').getAttribute('translate')).toBe('no')
  expect(button.getAttribute('aria-label')).toBe('收起系统助手-可乐')
})
it('renders only assistant markdown without changing user text', async () => {
  await open()
  const input = document.querySelector('textarea')
  input.value = '**我的问题** <b>原文</b>'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  document.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  pending.onEvent({ type: 'delta', text: '**总' })
  await flushPromises()
  expect(document.querySelector('.ai-markdown').textContent).toContain('总')
  pending.onEvent({ type: 'delta', text: '体**\n\n- 余位31个' })
  pending.onEvent({ type: 'done', success: true })
  pending.resolve()
  await flushPromises()
  expect(document.querySelector('.ai-markdown strong').textContent).toBe('总体')
  expect(document.querySelector('.ai-markdown li').textContent).toBe('余位31个')
  expect(document.querySelector('.ai-message.user p').textContent).toBe('**我的问题** <b>原文</b>')
  expect(document.querySelector('.ai-message.user p b')).toBeNull()
})
it('initializes lazily; collapse and navigation retain an active request and completion badge', async () => {
  expect(mock.get).not.toHaveBeenCalled()
  await open()
  await send()
  const id = pending.input.chatId
  document.querySelector('.ai-header [aria-label="收起系统助手-可乐"]').click()
  await router.push('/user/account')
  await flushPromises()
  expect(pending.signal.aborted).toBe(false)
  expect(document.querySelector('.ai-launcher').getAttribute('aria-label')).toContain('正在回答')
  pending.onEvent({ type: 'delta', text: '有空闲车位' })
  pending.onEvent({ type: 'done', success: true })
  pending.resolve()
  await flushPromises()
  expect(document.querySelector('.ai-unread')).not.toBeNull()
  await open()
  expect(document.querySelector('.ai-unread')).toBeNull()
  expect(document.querySelector('.ai-messages').textContent).toContain('有空闲车位')
  await send()
  expect(pending.input.chatId).toBe(id)
})
it('explicit stop aborts and restores the failed question', async () => {
  await open()
  await send()
  ;[...document.querySelectorAll('button')].find((b) => b.textContent === '停止生成').click()
  await flushPromises()
  expect(pending.signal.aborted).toBe(true)
  expect(document.querySelector('textarea').value).toBe('查询车位')
  expect(document.querySelector('.ai-messages').textContent).toContain('已停止生成')
})
it('unmount and page leave cancel without resurrecting private text', async () => {
  await open()
  await send()
  window.dispatchEvent(new Event('pagehide'))
  await flushPromises()
  expect(pending.signal.aborted).toBe(true)
  await open()
  expect(document.querySelector('textarea').value).toBe('')
  expect(document.querySelector('.ai-messages').textContent).not.toContain('查询车位')
  await send()
  wrapper.unmount()
  expect(pending.signal.aborted).toBe(true)
  wrapper = null
})
it('moves the same conversation to native fullscreen and lets Escape propagate', async () => {
  await open()
  await send()
  const host = document.createElement('section')
  document.body.append(host)
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: host })
  document.dispatchEvent(new Event('fullscreenchange'))
  await flushPromises()
  expect(host.querySelector('.ai-floating')).not.toBeNull()
  expect(pending.signal.aborted).toBe(false)
  const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
  document.dispatchEvent(event)
  await flushPromises()
  expect(event.defaultPrevented).toBe(false)
  expect(pending.signal.aborted).toBe(false)
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: null })
  document.dispatchEvent(new Event('fullscreenchange'))
  await flushPromises()
  expect(document.body.querySelector(':scope > .ai-floating')).not.toBeNull()
})
it('disabled AI is explicit and never calls the model', async () => {
  mock.get.mockResolvedValue({ data: { enabled: false, configured: false } })
  await open()
  expect(document.querySelector('.ai-window').textContent).toContain('系统助手尚未启用')
  expect(document.querySelector('textarea')).toBeNull()
  expect(mock.chat).not.toHaveBeenCalled()
})

it('a hidden failed answer creates an unread reminder and can be retried', async () => {
  await open(); await send()
  document.querySelector('.ai-header [aria-label="收起系统助手-可乐"]').click()
  pending.reject(new Error('fixture failure')); await flushPromises()
  expect(document.querySelector('.ai-unread')).not.toBeNull()
  await open()
  expect(document.querySelector('.ai-messages').textContent).toContain('回答未完成')
  expect(document.querySelector('textarea').value).toBe('查询车位')
})

async function actionCard(action = 'cancel') {
  await open(); await send()
  pending.onEvent({ type: 'action', data: { action, reservationId: 11, spaceId: 3, parkingId: 2, floor: 1, parkingName: 'test', spaceName: 'A412' } })
  pending.onEvent({ type: 'done', success: true }); pending.resolve(); await flushPromises()
  return document.querySelector('.ai-action button')
}
const booked = { id: 11, space_id: 3, flag: 0, reservation: 1 }
const entered = { ...booked, flag: 1 }
it.each(['reserve', 'cancel'])('blocks an old %s card after entry and refreshes the map', async (action) => {
  const changed = vi.fn(); window.addEventListener('parking-reservation-changed', changed)
  try {
    mock.current.mockResolvedValue({ data: [entered] })
    const button = await actionCard(action); button.click(); await flushPromises()
    expect(document.querySelector('.ai-action').textContent).toContain('车辆已入场，不能再次预约或取消预约')
    expect(document.querySelector('.ai-action button')).toBeNull()
    expect(mock.reserve).not.toHaveBeenCalled(); expect(mock.cancel).not.toHaveBeenCalled()
    expect(mock.success).not.toHaveBeenCalled(); expect(changed).toHaveBeenCalledOnce()
  } finally { window.removeEventListener('parking-reservation-changed', changed) }
})
it.each([false, true])('reconciles entry during a cancellation, response lost: %s', async (lost) => {
  mock.current.mockResolvedValueOnce({ data: [booked] }).mockResolvedValue({ data: [entered] })
  if (lost) mock.cancel.mockRejectedValue(new Error('response lost'))
  const button = await actionCard(); button.click(); await flushPromises()
  expect(mock.cancel).toHaveBeenCalledOnce()
  expect(document.querySelector('.ai-action').textContent).toContain('车辆已入场')
  expect(mock.success).not.toHaveBeenCalled()
})
it('does not claim cancellation success when a lost response leaves no active record', async () => {
  mock.current.mockResolvedValueOnce({ data: [booked] }).mockResolvedValue({ data: [] })
  mock.cancel.mockRejectedValue(new Error('response lost'))
  const button = await actionCard(); button.click(); await flushPromises()
  expect(document.querySelector('.ai-action').textContent).toContain('请查看预约状态')
  expect(document.querySelector('.ai-action').textContent).not.toContain('取消成功')
  expect(mock.success).not.toHaveBeenCalled()
})
it('normal cancellation only submits once and keeps confirmed feedback', async () => {
  let complete
  mock.current.mockResolvedValueOnce({ data: [booked] }).mockResolvedValue({ data: [] })
  mock.cancel.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const button = await actionCard(); button.click(); await flushPromises(); button.click(); await flushPromises()
  expect(mock.cancel).toHaveBeenCalledOnce()
  complete({ code: 0 }); await flushPromises()
  expect(mock.success).toHaveBeenCalledWith('已取消预约')
})
it.each([false, true])('reservation reconciles a matching record, response lost: %s', async (lost) => {
  mock.get.mockImplementation(url => Promise.resolve({ data: url === '/ai/status' ? { enabled: true, configured: true } : [{ id: 3, spaces_flag: 0 }] }))
  mock.current.mockResolvedValueOnce({ data: [] }).mockResolvedValue({ data: [booked] })
  if (lost) mock.reserve.mockRejectedValue(new Error('response lost'))
  const button = await actionCard('reserve'); button.click(); await flushPromises()
  expect(mock.reserve).toHaveBeenCalledWith({ id: 3 })
  if (lost) {
    expect(document.querySelector('.ai-action').textContent).toContain('已确认预约成功')
    expect(mock.success).not.toHaveBeenCalled()
  } else expect(mock.success).toHaveBeenCalledWith('预约成功')
})
