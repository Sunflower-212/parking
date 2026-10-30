import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import GateLane from '../src/components/GateLane.vue'
import UserAccount from '../src/views/user/UserAccount.vue'
import request from '../src/utils/request'
import { failureAlerts, closeFailure } from '../src/utils/request-error-notice'
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useRoute: () => ({ query: {} }),
}))
vi.mock('../src/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}))
vi.mock('../src/stores/userInfo', () => ({
  default: () => ({ info: { balance: 0, status: 0 }, setInfo: vi.fn() }),
}))
let wrapper
beforeEach(() => {
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  vi.clearAllMocks()
  request.get.mockResolvedValue({ data: {} })
})
afterEach(() => {
  wrapper?.unmount()
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  vi.unstubAllGlobals()
})
const options = {
  global: {
    stubs: Object.fromEntries(
      [
        'ElButton',
        'ElSelect',
        'ElOption',
        'ElAlert',
        'ElDivider',
        'ElInputNumber',
        'ElFormItem',
        'ElForm',
        'ElDialog',
      ].map((name) => [name, true]),
    ),
    directives: { loading: () => {} },
  },
}
it('shows image decode failures and resets the selected file', async () => {
  wrapper = shallowMount(GateLane, {
    props: { enabled: true, parkId: 1, mode: 'enter' },
    ...options,
  })
  vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('corrupt')))
  const target = { files: [new File(['bad'], 'bad.png', { type: 'image/png' })], value: 'selected' }
  await wrapper.vm.uploadFile({ target })
  expect(failureAlerts.value.at(-1)).toMatchObject({
    message: expect.stringContaining('图片无法读取'),
  })
  expect(target.value).toBe('')
  expect(request.post).not.toHaveBeenCalled()
})
it('does not upload a null encoded image and retains the photo for retry', async () => {
  wrapper = shallowMount(GateLane, {
    props: { enabled: true, parkId: 1, mode: 'enter' },
    ...options,
  })
  wrapper.vm.hasPhoto = true
  wrapper.get('canvas').element.toBlob = (callback) => callback(null)
  await wrapper.vm.submit()
  expect(request.post).not.toHaveBeenCalled()
  expect(wrapper.vm.hasPhoto).toBe(true)
  expect(wrapper.vm.busy).toBe(false)
  expect(failureAlerts.value.at(-1)).toMatchObject({ message: '车辆操作未完成，请重试' })
})
it('explains an unavailable payment form without reporting recharge success', async () => {
  wrapper = shallowMount(UserAccount, options)
  await flushPromises()
  wrapper.vm.pay()
  expect(failureAlerts.value.at(-1)).toMatchObject({ message: '支付页面不可用，请刷新充值单' })
})
it('keeps a successful membership write distinct from a failed account refresh', async () => {
  wrapper = shallowMount(UserAccount, options)
  await flushPromises()
  request.patch.mockResolvedValue({ code: 0 })
  request.get.mockRejectedValueOnce(new Error('refresh failed'))
  await wrapper.vm.membership()
  expect(ElMessage).toHaveBeenLastCalledWith(
    expect.objectContaining({ type: 'warning', message: '会员信息已更新，账户刷新失败，请重试' }),
  )
  expect(request.get.mock.lastCall[1]).toMatchObject({ feedback: false })
})
