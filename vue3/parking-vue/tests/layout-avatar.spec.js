import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppLayout from '../src/components/AppLayout.vue'
import useUser from '../src/stores/userInfo'
import request from '../src/utils/request'
import defaultAvatar from '../src/assets/default.png'

vi.mock('../src/utils/request', () => ({ default: { get: vi.fn() } }))

let wrapper
afterEach(() => wrapper?.unmount())

async function layout(info, admin = false) {
  request.get.mockResolvedValue({ data: info })
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/user/account', component: { template: '<div />' } }],
  })
  await router.push('/user/account')
  wrapper = mount(AppLayout, {
    props: { admin },
    global: {
      plugins: [pinia, router],
      stubs: Object.fromEntries(
        ['ElTag', 'ElButton', 'ElIcon', 'ElSkeleton', 'ElResult'].map((name) => [
          name,
          { template: '<div><slot /></div>' },
        ]),
      ),
    },
  })
  await flushPromises()
  return useUser(pinia)
}

it('shows the uploaded avatar and reacts to shared account updates', async () => {
  const store = await layout({ user_name: '用户', user_pic: '/first.png', status: 0 })
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe('/first.png')
  expect(wrapper.get('.profile-avatar').attributes('alt')).toBe('用户头像')
  store.setInfo({ ...store.info, user_pic: '/updated.png', status: 1 })
  await flushPromises()
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe('/updated.png')
  expect(wrapper.get('.profile').text()).toContain('会员')
})

it('uses the default avatar when absent or broken and retries a new uploaded URL', async () => {
  const store = await layout({ user_name: '用户', user_pic: null })
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe(defaultAvatar)
  store.setInfo({ ...store.info, user_pic: '/broken.png' })
  await flushPromises()
  await wrapper.get('.profile-avatar').trigger('error')
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe(defaultAvatar)
  store.setInfo({ ...store.info, user_pic: '/replacement.png' })
  await flushPromises()
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe('/replacement.png')
})

it('loads the avatar again when the layout is reopened', async () => {
  await layout({ user_name: '用户', user_pic: '/persisted.png' })
  wrapper.unmount()
  await layout({ user_name: '用户', user_pic: '/persisted.png' })
  expect(wrapper.get('.profile-avatar').attributes('src')).toBe('/persisted.png')
})

it('keeps administrator identity separate from the user avatar', async () => {
  await layout({ admin_name: '管理员' }, true)
  expect(request.get).toHaveBeenLastCalledWith('/admin/adminInfo')
  expect(wrapper.find('.profile-avatar').exists()).toBe(false)
  expect(wrapper.get('.profile-name').text()).toBe('管理员')
})
