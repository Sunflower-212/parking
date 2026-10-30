import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import PaymentResult from '../src/views/user/PaymentResult.vue'
import UserAccount from '../src/views/user/UserAccount.vue'
import useUser from '../src/stores/userInfo'
import request from '../src/utils/request'
import { feedback } from '../src/utils/request-error-notice'
import {
  rechargeId,
  paymentPath,
  rememberPayment,
  pendingPayment,
  consumePaymentReturn,
  rememberPaymentReturn,
} from '../src/utils/payment-context'
vi.mock('../src/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('../src/utils/request-error-notice', () => ({
  feedback: { success: vi.fn() },
  localFailure: vi.fn(),
}))
let wrapper, router, store, state
const slot = {
  template:
    '<div><slot /><slot name="sub-title" /><slot name="extra" /><slot name="actions" /></div>',
}
const stubs = Object.fromEntries(
  [
    'ElTag',
    'ElResult',
    'ElButton',
    'ElAlert',
    'ElDivider',
    'ElForm',
    'ElFormItem',
    'ElInputNumber',
    'ElDialog',
  ].map((n) => [n, slot]),
)
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  sessionStorage.clear()
  state = 'VERIFYING'
  request.post.mockImplementation(async () => ({
    data: { id: 7, amounts: 10, paymentState: state },
  }))
  request.get.mockImplementation(async (path) => ({
    data:
      path === '/user/userInfo'
        ? { id: 1, balance: 30, status: 0 }
        : path === '/system/capabilities'
          ? { alipay: true }
          : { id: 7, amounts: 10, paymentState: state },
  }))
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
})
async function setup(component = PaymentResult) {
  const pinia = createPinia()
  store = useUser(pinia)
  store.setInfo({ id: 1, balance: 20, status: 0 })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/user/paymentResult', component: PaymentResult },
      { path: '/user/account', component: { template: '<div>account</div>' } },
    ],
  })
  await router.push('/user/paymentResult?rechargeId=7&trade_status=TRADE_SUCCESS')
  wrapper = mount(component, {
    global: { plugins: [pinia, router], stubs, directives: { loading: () => {} } },
  })
  await flushPromises()
  return wrapper.vm
}
it('does not trust successful browser return parameters as credit confirmation', async () => {
  const vm = await setup()
  expect(vm.title).toBe('到账确认中')
  expect(store.info.balance).toBe(20)
  expect(feedback.success).not.toHaveBeenCalled()
  expect(router.currentRoute.value.path).toBe('/user/paymentResult')
})
it('refreshes shared account state and replaces the result route only after confirmed credit', async () => {
  rememberPayment(1, { rechargeId: 7 })
  await setup()
  state = 'PAID'
  await vi.advanceTimersByTimeAsync(3000)
  await flushPromises()
  expect(store.info.balance).toBe(30)
  expect(router.currentRoute.value.path).toBe('/user/account')
  expect(feedback.success).toHaveBeenCalledOnce()
  expect(pendingPayment(1)).toBe(null)
})
it('stops automatically after 60 seconds and polls the database without repeating provider checks', async () => {
  const vm = await setup()
  await vi.advanceTimersByTimeAsync(65000)
  expect(vm.waiting).toBe(true)
  expect(request.post).toHaveBeenCalledTimes(1)
  const calls = request.get.mock.calls.length
  await vi.advanceTimersByTimeAsync(30000)
  expect(request.get.mock.calls).toHaveLength(calls)
  expect(feedback.success).not.toHaveBeenCalled()
})
it('keeps confirmed credit distinct from an account refresh failure', async () => {
  state = 'PAID'
  request.get.mockRejectedValueOnce(new Error('offline'))
  const vm = await setup()
  expect(vm.status.paymentState).toBe('PAID')
  expect(vm.error).toContain('充值已到账')
  expect(router.currentRoute.value.path).toBe('/user/paymentResult')
  await vm.retry()
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/user/account')
  expect(request.post).toHaveBeenCalledTimes(1)
})
it('ignores responses and clears polling after unmount', async () => {
  let resolve
  request.post.mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  await setup()
  wrapper.unmount()
  resolve({ data: { paymentState: 'PAID' } })
  await flushPromises()
  await vi.advanceTimersByTimeAsync(65000)
  expect(request.get).not.toHaveBeenCalled()
  expect(feedback.success).not.toHaveBeenCalled()
})
it('submits the payment form in the same tab once and remembers the owned recharge', async () => {
  // Account legacy return redirect is irrelevant to this direct submission check.
  await setup(UserAccount)
  const form = document.createElement('form')
  form.action = 'https://openapi-sandbox.dl.alipaydev.com/gateway.do'
  form.submit = vi.fn()
  const container = document.createElement('div')
  container.appendChild(form)
  wrapper.vm.formContainer = container
  wrapper.vm.payment = { rechargeId: 7 }
  wrapper.vm.pay()
  wrapper.vm.pay()
  expect(form.target).toBe('_self')
  expect(form.submit).toHaveBeenCalledTimes(1)
  expect(pendingPayment(1)).toBe(7)
})
it('validates return IDs, isolates pending orders by user and allows only a fixed login return path', () => {
  for (const value of ['0', '-1', '2147483648', 'https://evil.invalid', ['7'], '7.0'])
    expect(rechargeId({ rechargeId: value })).toBe(null)
  expect(rechargeId({ out_trade_no: '7' })).toBe(7)
  rememberPayment(1, { rechargeId: 7 })
  expect(pendingPayment(2)).toBe(null)
  rememberPaymentReturn({ rechargeId: '7', redirect: 'https://evil.invalid' })
  expect(consumePaymentReturn()).toBe(paymentPath(7))
  expect(consumePaymentReturn()).toBe(null)
})
