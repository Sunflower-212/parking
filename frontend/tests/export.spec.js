import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ExportUsers from '../src/components/ExportUsers.vue'
import request from '../src/utils/request'
vi.mock('../src/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('jwt-decode', () => ({ jwtDecode: () => ({ claims: { id: 1 } }) }))
vi.mock('../src/stores/token', () => ({ useTokenStore: () => ({ token: 'test' }) }))
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))
let wrapper
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.useFakeTimers()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
})
function mountExport() {
  wrapper = mount(ExportUsers, {
    props: { filters: { user_name: 'test' } },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>',
        },
      },
    },
  })
}
it('reuses the idempotency key when task creation has an uncertain result', async () => {
  request.get.mockResolvedValue({ data: { exports: true } })
  request.post
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValueOnce({ data: { id: 'job-1' } })
  mountExport()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(request.post.mock.calls[0][2].headers['Idempotency-Key']).toBe(
    request.post.mock.calls[1][2].headers['Idempotency-Key'],
  )
  expect(wrapper.text()).toContain('处理中')
  expect(sessionStorage.getItem('parking-export:1')).toBe('job-1')
})
it('resumes a saved task and stops polling after unmount', async () => {
  sessionStorage.setItem('parking-export:1', 'job-2')
  request.get.mockResolvedValue({ data: { status: 'SENT' } })
  mountExport()
  await flushPromises()
  expect(request.get).toHaveBeenCalledTimes(1)
  wrapper.unmount()
  await vi.advanceTimersByTimeAsync(5000)
  expect(request.get).toHaveBeenCalledTimes(1)
})
it('shows a download action only after completion', async () => {
  sessionStorage.setItem('parking-export:1', 'job-3')
  request.get.mockResolvedValue({ data: { status: 'DONE' } })
  mountExport()
  await flushPromises()
  expect(wrapper.text()).toContain('下载文件')
  expect(wrapper.text()).not.toContain('处理中')
})
it('keeps an uncertain creation key across page navigation', async () => {
  request.get.mockResolvedValue({ data: { exports: true } })
  request.post
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValueOnce({ data: { id: 'job-4' } })
  mountExport()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  const key = request.post.mock.calls[0][2].headers['Idempotency-Key']
  wrapper.unmount()
  mountExport()
  expect(wrapper.text()).toContain('重试上次导出')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(request.post.mock.calls[1][2].headers['Idempotency-Key']).toBe(key)
  expect(sessionStorage.getItem('parking-export:1:pending')).toBeNull()
})
it('does not duplicate a known pending job and can use direct export after background tasks are disabled', async () => {
  let enabled = true
  sessionStorage.setItem('parking-export:1', 'job-5')
  request.get.mockImplementation((url) => {
    if (url.endsWith('/capabilities')) return Promise.resolve({ data: { exports: enabled } })
    if (url === '/user/export') return Promise.reject(new Error('download interrupted'))
    return Promise.resolve({ data: { status: 'SENT' } })
  })
  mountExport()
  await flushPromises()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(request.post).not.toHaveBeenCalled()
  enabled = false
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(request.get.mock.calls.some(([url]) => url === '/user/export')).toBe(true)
  await vi.advanceTimersByTimeAsync(5000)
  expect(wrapper.text()).toContain('后台导出已暂停')
  expect(request.post).not.toHaveBeenCalled()
})
