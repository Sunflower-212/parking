import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppLayout from '../src/components/AppLayout.vue'
import UserAccount from '../src/views/user/UserAccount.vue'
import useUser from '../src/stores/userInfo'
import { useTokenStore } from '../src/stores/token'
import request from '../src/utils/request'
import { feedback } from '../src/utils/request-error-notice'

vi.mock('../src/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('../src/utils/request-error-notice', () => ({
  feedback: { success: vi.fn(), warning: vi.fn() },
  localFailure: vi.fn(),
}))

let wrapper, info, current
const ordinary = {
  id: 1,
  user_name: 'membertest',
  status: 0,
  vip_start_time: '2025-01-01T00:00:00',
  vip_end_time: '2025-02-01T00:00:00',
}
const renewed = { ...ordinary, status: 1, vip_end_time: '2099-02-01T00:00:00' }
const slot = { template: '<div><slot /><slot name="actions" /></div>' }
const stubs = Object.fromEntries(
  [
    'ElTag',
    'ElButton',
    'ElIcon',
    'ElSkeleton',
    'ElResult',
    'ElAlert',
    'ElDivider',
    'ElForm',
    'ElFormItem',
    'ElInputNumber',
    'ElDialog',
  ].map((name) => [name, slot]),
)

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  current = { ...ordinary }
  request.get.mockImplementation(async (path) => ({
    data:
      path === '/system/capabilities'
        ? { alipay: true }
        : path.endsWith('/plans')
          ? [
              { code: 'MONTHLY', name: '月度', price: 188, months: 1 },
              { code: 'QUARTERLY', name: '季度', price: 488, months: 3 },
              { code: 'YEARLY', name: '年度', price: 1888, months: 12 },
            ]
          : path.endsWith('/current')
            ? null
            : { ...current },
  }))
  request.post.mockResolvedValue({ code: 0, data: { state: 'SUCCEEDED' } })
})
afterEach(() => wrapper?.unmount())

async function setup(admin = false, role = -1) {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/user/account', component: UserAccount },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  const token = `e30.${btoa(JSON.stringify({ claims: { permissions: role } }))}.signature`
  useTokenStore(pinia).setToken(token)
  info = useUser(pinia)
  await router.push('/user/account')
  await router.isReady()
  wrapper = mount(AppLayout, {
    props: { admin },
    global: { plugins: [pinia, router], stubs, directives: { loading: () => {} } },
  })
  await flushPromises()
  return token
}

it.each([false, true])(
  'updates the header after membership write, renewal=%s, without changing the permission token',
  async (renew) => {
    if (!renew) current = { ...ordinary, vip_start_time: null, vip_end_time: null }
    const token = await setup()
    expect(wrapper.get('.profile').text()).toContain('普通用户')
    request.post.mockImplementationOnce(async () => {
      current = { ...renewed }
      return { code: 0, data: { state: 'SUCCEEDED', renewed: true } }
    })
    await wrapper.findComponent(UserAccount).vm.membership()
    await flushPromises()
    expect(request.post.mock.calls[0][0]).toBe('/user/membership/orders')
    expect(request.post.mock.calls[0][1]).toEqual({ planCode: 'MONTHLY' })
    expect(request.post.mock.calls[0][2].headers['Idempotency-Key']).toBeTruthy()
    expect(wrapper.get('.profile').text()).toContain('会员')
    expect(wrapper.get('.profile').text()).not.toContain('普通用户')
    expect(info.info.vip_end_time).toBe(renewed.vip_end_time)
    expect(useTokenStore().token).toBe(token)
  },
)

it('discards an account response started before renewal even if cancellation is ignored', async () => {
  await setup()
  let resolveOld
  request.get.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOld = resolve
      }),
  )
  const account = wrapper.findComponent(UserAccount)
  const pending = account.vm.refresh(true)
  const signal = request.get.mock.lastCall[1].signal
  request.post.mockImplementationOnce(async () => {
    current = { ...renewed }
    return { code: 0, data: { state: 'SUCCEEDED', renewed: true } }
  })
  await account.vm.membership()
  expect(signal.aborted).toBe(true)
  resolveOld({ data: { ...ordinary } })
  await pending
  await flushPromises()
  expect(info.info.status).toBe(1)
  expect(wrapper.get('.profile').text()).toContain('会员')
  expect(feedback.warning).not.toHaveBeenCalled()
})

it('keeps ordinary status when the membership write fails', async () => {
  await setup()
  request.post.mockRejectedValueOnce(new Error('membership failed'))
  await wrapper.findComponent(UserAccount).vm.membership()
  expect(info.info.status).toBe(0)
  expect(wrapper.get('.profile').text()).toContain('普通用户')
  expect(feedback.success).not.toHaveBeenCalled()
})

it('cancels pending account refresh on unmount without replacing shared information', async () => {
  await setup()
  let resolveOld
  request.get.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOld = resolve
      }),
  )
  const pending = wrapper.findComponent(UserAccount).vm.refresh(true)
  const signal = request.get.mock.lastCall[1].signal
  wrapper.unmount()
  wrapper = undefined
  resolveOld({ data: renewed })
  await pending
  expect(signal.aborted).toBe(true)
  expect(info.info.status).toBe(0)
})

it.each([
  [0, '停车场管理员'],
  [1, '超级管理员'],
])('keeps administrator role %s separate from membership', async (role, label) => {
  await setup(true, role)
  info.setInfo(renewed)
  await flushPromises()
  expect(wrapper.get('.profile').text()).toContain(label)
  expect(wrapper.get('.profile').text()).not.toContain('会员')
})

it('shows three priced plans and removes the inactive membership description', async () => {
  current = {
    ...ordinary,
    balance: 100,
    reserved_balance: 0,
    available_balance: 100,
    vip_start_time: null,
    vip_end_time: null,
  }
  await setup()
  expect(wrapper.findAll('.membership-plan')).toHaveLength(3)
  expect(wrapper.text()).toContain('188')
  expect(wrapper.text()).toContain('488')
  expect(wrapper.text()).toContain('1,888')
  expect(wrapper.text()).toContain('需补差')
  expect(wrapper.text()).not.toContain('尚未开通会员')
  expect(wrapper.text()).not.toContain('会员天数')
})
it('reuses the original key after a lost response and blocks plan changes until reconciliation', async () => {
  await setup()
  request.post.mockRejectedValueOnce(Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }))
  const account = wrapper.findComponent(UserAccount)
  await account.vm.membership()
  const firstKey = request.post.mock.calls[0][2].headers['Idempotency-Key']
  expect(account.vm.uncertainAttempt).toBe(true)
  expect(wrapper.get('.membership-plan').attributes('disabled')).toBeDefined()
  await account.vm.membership()
  expect(request.post.mock.calls[1][2].headers['Idempotency-Key']).toBe(firstKey)
})
it('recovers a pending order and retains it when the payment form fails', async () => {
  await setup()
  const order = {
    id: 'test-order',
    planCode: 'MONTHLY',
    planName: '月度',
    state: 'PENDING',
    reservedAmount: 100,
    supplementAmount: 88,
    rechargeId: 99,
    paymentState: 'PENDING',
    expiresAt: '2099-01-01T00:00:00',
  }
  request.post
    .mockResolvedValueOnce({ data: order })
    .mockRejectedValueOnce(new Error('form unavailable'))
  request.get.mockImplementation(async (path) => ({
    data: path.endsWith('/current')
      ? order
      : path.endsWith('/plans')
        ? [{ code: 'MONTHLY', name: '月度', price: 188, months: 1 }]
        : current,
  }))
  await wrapper.findComponent(UserAccount).vm.membership()
  expect(wrapper.find('.pending-membership').exists()).toBe(true)
  expect(wrapper.text()).toContain('继续支付')
  expect(wrapper.findComponent(UserAccount).vm.pendingMembership.id).toBe('test-order')
})

it('restores an ambiguous attempt after the account identity arrives on a fresh page', async () => {
  current = {}
  sessionStorage.setItem(
    'parking-membership:1',
    JSON.stringify({ planCode: 'QUARTERLY', key: 'saved-purchase-key-1234' }),
  )
  await setup()
  info.setInfo({ ...ordinary, id: 1 })
  await flushPromises()
  const account = wrapper.findComponent(UserAccount)
  expect(account.vm.selectedPlan).toBe('QUARTERLY')
  expect(account.vm.uncertainAttempt).toBe(true)
  await account.vm.membership()
  expect(request.post.mock.calls[0][2].headers['Idempotency-Key']).toBe('saved-purchase-key-1234')
})
