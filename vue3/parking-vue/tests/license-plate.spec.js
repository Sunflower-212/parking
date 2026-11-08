import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { readFileSync } from 'node:fs'
import LicensePlateInput from '../src/components/LicensePlateInput.vue'
import { pages } from '../src/config/pages'
import {
  plateType,
  validPlate,
  provinces,
  plateCharacters,
  normalizePlate,
} from '../src/utils/license-plate'
const cases = JSON.parse(
  readFileSync('../../parking/parking/src/test/resources/license-plates.json', 'utf8'),
)
let wrapper
const mountPlate = (value = '') =>
  (wrapper = mount(LicensePlateInput, {
    props: {
      modelValue: value,
      'onUpdate:modelValue': (value) => wrapper.setProps({ modelValue: value }),
    },
    global: { plugins: [ElementPlus] },
  }))
const key = async (c) => {
  await wrapper.get(`[aria-label="输入${c}"]`).trigger('click')
  await flushPromises()
}
const change = async (type) => {
  wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', type)
  await flushPromises()
}
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
it('matches backend fixtures for all 31 provinces and three structures', () => {
  expect(provinces).toHaveLength(31)
  for (const item of cases) expect(plateType(item.plate), item.plate).toBe(item.type)
  expect(normalizePlate('皖h88888')).toBe('皖H-88888')
  expect(validPlate('沪A-GA1234', 'ordinary')).toBe(false)
  expect(validPlate(undefined, 'ordinary')).toBe(false)
  const validator = pages.users.fields.find((f) => f.key === 'plate_num').rules[0].validator
  for (const item of cases) {
    if (!item.plate) continue
    const done = vi.fn()
    validator(null, item.plate, done)
    expect(!!done.mock.calls[0][0]).toBe(item.type === null)
  }
})
it('restricts keys by position and limits ordinary suffix to two letters', async () => {
  mountPlate()
  expect(wrapper.get('[data-cell="0"]').classes()).toContain('placeholder')
  expect(wrapper.props('modelValue')).toBe('')
  await wrapper.get('[data-cell="0"]').trigger('click')
  expect(wrapper.findAll('.plate-keys button')).toHaveLength(31)
  await key('皖')
  expect(wrapper.get('[data-cell="0"]').classes()).not.toContain('placeholder')
  expect(wrapper.find('[aria-label="输入I"]').exists()).toBe(false)
  expect(wrapper.find('[aria-label="输入1"]').exists()).toBe(false)
  for (const c of 'HAB123') await key(c)
  expect(wrapper.props('modelValue')).toBe('皖H-AB123')
  expect(wrapper.vm.validate()).toBe(true)
  await wrapper.get('[data-cell="4"]').trigger('click')
  expect(wrapper.find('[aria-label="输入C"]').exists()).toBe(false)
  await wrapper.get('[data-cell="2"]').trigger('click')
  expect(wrapper.find('[aria-label="输入C"]').exists()).toBe(true)
  expect(plateCharacters('small', 2)).not.toContain('1')
  expect(plateCharacters('small', 4)).not.toContain('A')
  expect(plateCharacters('large', 7)).not.toContain('1')
  expect(plateCharacters('large', 6)).not.toContain('A')
})
it('confirms a type change, keeps prefix, permits backspace and clear', async () => {
  mountPlate('皖H-88888')
  const confirm = vi
    .spyOn(ElMessageBox, 'confirm')
    .mockRejectedValueOnce('cancel')
    .mockResolvedValueOnce('confirm')
  await change('small')
  expect(wrapper.props('modelValue')).toBe('皖H-88888')
  await change('small')
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(wrapper.findAll('[data-cell]')).toHaveLength(8)
  for (const c of 'GA1234') await key(c)
  expect(wrapper.props('modelValue')).toBe('皖H-GA1234')
  expect(wrapper.vm.validate()).toBe(true)
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '退格')
    .trigger('click')
  expect(wrapper.vm.validate()).toBe(false)
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '清空')
    .trigger('click')
  expect(wrapper.findAll('[data-cell]').every((b) => !b.text() || b.text() === '省')).toBe(true)
})
it('infers supported types and preserves unsupported historical values', async () => {
  mountPlate('粤B-12345H')
  expect(wrapper.findAll('[data-cell]')).toHaveLength(8)
  expect(wrapper.vm.validate()).toBe(true)
  await wrapper.setProps({ modelValue: '旧车牌-123456789' })
  expect(wrapper.get('.plate-legacy').text()).toContain('旧车牌-123456789')
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  expect(wrapper.vm.validate()).toBe(false)
  await wrapper.setProps({ modelValue: '皖H-123456789' })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm')
  await change('small')
  expect(wrapper.props('modelValue')).toBe('皖H-      ')
})
