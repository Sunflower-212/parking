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

vi.mock('../src/utils/request', () => ({ default: { get: vi.fn(), patch: vi.fn() } }))
vi.mock('../src/utils/request-error-notice', () => ({
  feedback: { success: vi.fn(), warning: vi.fn() },
  localFailure: vi.fn(),
}))

let wrapper, info, current
const ordinary = { user_name: 'membertest', status: 0, vip_start_time: '2025-01-01T00:00:00', vip_end_time: '2025-02-01T00:00:00' }
const renewed = { ...ordinary, status: 1, vip_end_time: '2099-02-01T00:00:00' }
const slot = { template: '<div><slot /><slot name="actions" /></div>' }
const stubs = Object.fromEntries([
  'ElTag', 'ElButton', 'ElIcon', 'ElSkeleton', 'ElResult', 'ElAlert',
  'ElDivider', 'ElForm', 'ElFormItem', 'ElInputNumber', 'ElDialog',
].map(name => [name, slot]))

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  current = { ...ordinary }
  request.get.mockImplementation(async path => ({ data: path === '/system/capabilities' ? {} : { ...current } }))
  request.patch.mockResolvedValue({ code: 0 })
})
afterEach(() => wrapper?.unmount())

async function setup(admin = false, role = -1) {
  const pinia = createPinia()
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/user/account', component: UserAccount },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ] })
  const token = `e30.${btoa(JSON.stringify({ claims: { permissions: role } }))}.signature`
  useTokenStore(pinia).setToken(token)
  info = useUser(pinia)
  await router.push('/user/account')
  await router.isReady()
  wrapper = mount(AppLayout, { props: { admin }, global: { plugins: [pinia, router], stubs, directives: { loading: () => {} } } })
  await flushPromises()
  return token
}

it.each([false, true])('updates the header after membership write, renewal=%s, without changing the permission token', async (renew) => {
  if (!renew) current = { ...ordinary, vip_start_time: null, vip_end_time: null }
  const token = await setup()
  expect(wrapper.get('.profile').text()).toContain('普通用户')
  request.patch.mockImplementationOnce(async () => { current = { ...renewed }; return { code: 0 } })
  await wrapper.findComponent(UserAccount).vm.membership()
  await flushPromises()
  expect(request.patch.mock.calls[0][0]).toBe(renew ? '/user/vipRenewal' : '/user/getVip')
  expect(wrapper.get('.profile').text()).toContain('会员')
  expect(wrapper.get('.profile').text()).not.toContain('普通用户')
  expect(info.info.vip_end_time).toBe(renewed.vip_end_time)
  expect(useTokenStore().token).toBe(token)
})

it('discards an account response started before renewal even if cancellation is ignored', async () => {
  await setup()
  let resolveOld
  request.get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const account = wrapper.findComponent(UserAccount)
  const pending = account.vm.refresh(true)
  const signal = request.get.mock.lastCall[1].signal
  request.patch.mockImplementationOnce(async () => { current = { ...renewed }; return { code: 0 } })
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
  request.patch.mockRejectedValueOnce(new Error('membership failed'))
  await wrapper.findComponent(UserAccount).vm.membership()
  expect(info.info.status).toBe(0)
  expect(wrapper.get('.profile').text()).toContain('普通用户')
  expect(feedback.success).not.toHaveBeenCalled()
})

it('cancels pending account refresh on unmount without replacing shared information', async () => {
  await setup()
  let resolveOld
  request.get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const pending = wrapper.findComponent(UserAccount).vm.refresh(true)
  const signal = request.get.mock.lastCall[1].signal
  wrapper.unmount()
  wrapper = undefined
  resolveOld({ data: renewed })
  await pending
  expect(signal.aborted).toBe(true)
  expect(info.info.status).toBe(0)
})

it.each([[0, '停车场管理员'], [1, '超级管理员']])('keeps administrator role %s separate from membership', async (role, label) => {
  await setup(true, role)
  info.setInfo(renewed)
  await flushPromises()
  expect(wrapper.get('.profile').text()).toContain(label)
  expect(wrapper.get('.profile').text()).not.toContain('会员')
})
