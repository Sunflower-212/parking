import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppLayout from '../src/components/AppLayout.vue'

vi.mock('../src/utils/request', () => ({
  default: { get: vi.fn().mockResolvedValue({ data: { user_name: 'test', status: 0 } }) },
}))

let wrapper
afterEach(() => wrapper?.unmount())

it('uses route titles before menu titles and updates the breadcrumb on navigation', async () => {
  const page = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/user/paymentResult', component: page, meta: { title: '充值结果' } },
      { path: '/user/account', component: page },
      { path: '/user/info', component: page, meta: { title: '路由标题' } },
      { path: '/user/unknown', component: page },
    ],
  })
  await router.push('/user/paymentResult')
  const slot = { template: '<div><slot /></div>' }
  wrapper = mount(AppLayout, {
    global: {
      plugins: [createPinia(), router],
      stubs: Object.fromEntries(['ElTag', 'ElButton', 'ElIcon', 'ElSkeleton', 'ElResult'].map(name => [name, slot])),
    },
  })
  await flushPromises()
  expect(wrapper.get('.breadcrumb strong').text()).toBe('充值结果')
  await router.push('/user/account')
  expect(wrapper.get('.breadcrumb strong').text()).toBe('个人账户')
  await router.push('/user/info')
  expect(wrapper.get('.breadcrumb strong').text()).toBe('路由标题')
  await router.push('/user/unknown')
  expect(wrapper.get('.breadcrumb strong').text()).toBe('停车管理')
})
