import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import AuditLog from '../src/views/admin/AuditLog.vue'
import request from '../src/utils/request'
import { showRequestError, failureAlerts, closeFailure } from '../src/utils/request-error-notice'
import { ElMessage } from 'element-plus'
vi.mock('../src/utils/request', () => ({ default: { get: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))
let wrapper
const mountAudit = () =>
  shallowMount(AuditLog, {
    global: {
      stubs: Object.fromEntries(
        [
          'ElAlert',
          'ElDatePicker',
          'ElFormItem',
          'ElInputNumber',
          'ElOption',
          'ElSelect',
          'ElInput',
          'ElTableColumn',
          'ElTag',
          'ElButton',
          'ElDialog',
        ].map((name) => [name, true]),
      ),
      directives: { loading: () => {} },
    },
  })
beforeEach(() => {
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  vi.clearAllMocks()
  request.get.mockResolvedValue({ data: { total: 0, items: [] } })
})
afterEach(() => {
  wrapper?.unmount()
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
})
it('loads bounded audit pagination and search resets the page', async () => {
  wrapper = mountAudit()
  await flushPromises()
  expect(request.get.mock.calls[0][0]).toBe('/admin/audit/page')
  expect(request.get.mock.calls[0][1].params).toMatchObject({ pageNum: 1, pageSize: 20 })
  wrapper.vm.pagination.pageNum = 3
  await wrapper.vm.load(true)
  expect(request.get.mock.lastCall[1].params.pageNum).toBe(1)
})
it('does not apply old detail responses and cancels them on close', async () => {
  wrapper = mountAudit()
  await flushPromises()
  const pending = []
  request.get.mockImplementation(
    (url, options) => new Promise((resolve) => pending.push({ url, options, resolve })),
  )
  const first = wrapper.vm.open({ eventId: 'first' })
  const second = wrapper.vm.open({ eventId: 'second' })
  expect(pending[0].options.signal.aborted).toBe(true)
  pending[1].resolve({ data: { eventId: 'second' } })
  await second
  pending[0].resolve({ data: { eventId: 'first' } })
  await first
  expect(wrapper.vm.detail.eventId).toBe('second')
  const third = wrapper.vm.open({ eventId: 'third' })
  wrapper.vm.close()
  pending[2].resolve({ data: { eventId: 'third' } })
  await third
  expect(pending[2].options.signal.aborted).toBe(true)
  expect(wrapper.vm.detail).toBe(null)
})
it('ignores invalid correlation IDs and keeps markup as plain message text', () => {
  showRequestError('服务异常', 'id\nforged')
  expect(failureAlerts.value[0].requestId).toBe(null)
  showRequestError('<script>bad</script>', 'safe-id')
  expect(failureAlerts.value.at(-1)).toMatchObject({
    message: '<script>bad</script>',
    requestId: 'safe-id',
  })
})
