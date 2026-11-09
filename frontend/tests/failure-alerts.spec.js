import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElAlert, ElMessage } from 'element-plus'
import FailureAlerts from '../src/components/FailureAlerts.vue'
import { failureAlerts, showRequestError, closeFailure } from '../src/utils/request-error-notice'
vi.mock('element-plus', async (original) => ({ ...(await original()), ElMessage: vi.fn() }))
let wrapper
beforeEach(() => {
  failureAlerts.value.forEach((n) => closeFailure(n.id))
  vi.clearAllMocks()
})
afterEach(() => {
  wrapper?.unmount()
  failureAlerts.value.forEach((n) => closeFailure(n.id))
  vi.useRealTimers()
})
function mountAlerts() {
  wrapper = mount(FailureAlerts, { global: { components: { ElAlert } } })
}
it('renders a real error Alert with icon, text-only content and close control', async () => {
  mountAlerts()
  showRequestError('<script>unsafe</script>', 'test-id')
  await flushPromises()
  const alert = document.querySelector('.failure-alerts .el-alert')
  expect(alert.classList.contains('el-alert--error')).toBe(true)
  expect(alert.querySelector('.el-alert__icon')).not.toBe(null)
  expect(alert.querySelector('script')).toBe(null)
  expect(alert.textContent).toContain('<script>unsafe</script>')
  expect(alert.querySelector('details').open).toBe(false)
  alert.querySelector('.el-alert__close-btn').click()
  await flushPromises()
  expect(failureAlerts.value).toHaveLength(0)
})
it('copies the correlation ID and explains clipboard failure', async () => {
  const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  mountAlerts()
  showRequestError('操作失败', 'copy-id')
  await flushPromises()
  document.querySelector('.feedback-copy').click()
  await flushPromises()
  expect(ElMessage).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'warning' }))
  document.querySelector('.feedback-copy').click()
  await flushPromises()
  expect(writeText).toHaveBeenCalledWith('copy-id')
  expect(ElMessage).toHaveBeenLastCalledWith(
    expect.objectContaining({ type: 'success', message: '请求 ID 已复制' }),
  )
  expect(failureAlerts.value).toHaveLength(0)
})
it('dismisses automatically at six seconds and never renders a legacy error Message', async () => {
  vi.useFakeTimers()
  mountAlerts()
  showRequestError('保存失败')
  await flushPromises()
  expect(document.querySelector('.failure-alerts')).not.toBe(null)
  vi.advanceTimersByTime(6000)
  await flushPromises()
  expect(document.querySelector('.failure-alerts')).toBe(null)
  expect(ElMessage).not.toHaveBeenCalled()
})
it('replaces an older failure and gives the latest Alert its own six-second lifetime', async () => {
  vi.useFakeTimers()
  mountAlerts()
  showRequestError('第一次失败')
  vi.advanceTimersByTime(5000)
  showRequestError('第二次失败')
  await flushPromises()
  expect(failureAlerts.value).toHaveLength(1)
  expect(document.querySelector('.el-alert__title').textContent).toBe('第二次失败')
  vi.advanceTimersByTime(1000)
  expect(failureAlerts.value).toHaveLength(1)
  vi.advanceTimersByTime(5000)
  expect(failureAlerts.value).toHaveLength(0)
})
