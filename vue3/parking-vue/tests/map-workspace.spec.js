import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import SpaceWorkspace from '../src/components/SpaceWorkspace.vue'
import request from '../src/utils/request'
import { ElMessageBox } from 'element-plus'
vi.mock('../src/utils/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))
let wrapper
const cell = {
  id: 1,
  spaces_name: 'A1',
  spaces_row: 1,
  spaces_col: 1,
  spaces_flag: 0,
  editable: true,
}
const snapshot = { revision: 'v1', items: [cell] }
const options = {
  global: {
    stubs: Object.fromEntries(
      [
        'ElButton',
        'ElAlert',
        'ElSelect',
        'ElOption',
        'ElForm',
        'ElFormItem',
        'ElInput',
        'ElTag',
        'ElTable',
        'ElTableColumn',
        'ElCheckbox',
        'ElPagination',
        'ElDialog',
        'ElInputNumber',
        'ElRadioGroup',
        'ElRadioButton',
      ].map((n) => [n, true]),
    ),
    directives: { loading: () => {} },
  },
}
async function mount() {
  wrapper = shallowMount(SpaceWorkspace, { props: { admin: true }, ...options })
  await flushPromises()
  return wrapper.vm
}
beforeEach(() => {
  vi.clearAllMocks()
  request.get.mockImplementation((url) =>
    Promise.resolve({ data: url === '/parking/catalog' ? [{ id: 1, park_storey: 2 }] : snapshot }),
  )
  ElMessageBox.confirm.mockResolvedValue('confirm')
})
afterEach(() => wrapper?.unmount())
it('cancels and ignores old floor responses, including after unmount', async () => {
  const vm = await mount(),
    pending = []
  request.get.mockImplementation(
    (url, options) => new Promise((resolve) => pending.push({ options, resolve })),
  )
  const first = vm.refresh(),
    second = vm.refresh()
  expect(pending[0].options.signal.aborted).toBe(true)
  pending[1].resolve({ data: { revision: 'new', items: [] } })
  await second
  pending[0].resolve({ data: snapshot })
  await first
  expect(vm.server.revision).toBe('new')
  const last = vm.refresh()
  wrapper.unmount()
  pending[2].resolve({ data: snapshot })
  await last
  expect(pending[2].options.signal.aborted).toBe(true)
  expect(vm.server.revision).toBe('new')
})
it('preserves draft while occupancy refresh blocks edits and detects revision conflicts', async () => {
  const vm = await mount()
  await vm.startEdit()
  vm.commit([{ ...cell, spaces_name: '改名' }])
  request.get.mockResolvedValueOnce({
    data: { revision: 'v1', items: [{ ...cell, spaces_flag: 1, editable: false }] },
  })
  await vm.refresh(true)
  expect(vm.draft[0].spaces_name).toBe('改名')
  expect(vm.blocked).toEqual([1])
  expect(vm.saveBlocked).toBe(true)
  request.get.mockResolvedValueOnce({ data: { revision: 'v2', items: [cell] } })
  await vm.refresh(true)
  expect(vm.conflict).toContain('修改')
})
it('blocks duplicate saves and verifies server state after a lost response without replay', async () => {
  const vm = await mount()
  await vm.startEdit()
  vm.commit([{ ...cell, spaces_name: '改名' }])
  let reject
  request.post.mockImplementation(() => new Promise((_, r) => (reject = r)))
  const first = vm.save()
  await vm.save()
  expect(request.post).toHaveBeenCalledTimes(1)
  reject(new Error('connection lost'))
  await first
  expect(vm.uncertain).toBe(true)
  expect(vm.dirty).toBe(true)
  expect(vm.saveBlocked).toBe(true)
  expect(request.get.mock.lastCall[0]).toBe('/spaces/editor')
  await vm.save()
  expect(request.post).toHaveBeenCalledTimes(1)
})
it('requires explicit discard before switching floors and keeps drafts on cancel', async () => {
  const vm = await mount()
  await vm.startEdit()
  vm.commit([{ ...cell, spaces_name: '改名' }])
  ElMessageBox.confirm.mockRejectedValueOnce('cancel')
  await vm.changeFloor(2)
  expect(vm.floor).toBe(1)
  expect(vm.dirty).toBe(true)
  await vm.changeFloor(2)
  expect(vm.floor).toBe(2)
  expect(vm.editing).toBe(false)
})
