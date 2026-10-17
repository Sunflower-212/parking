import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'
import { mount } from '@vue/test-utils'
import request from '../src/utils/request'
import { usePagedQuery } from '../src/composables/usePagedQuery'
import PagedTable from '../src/components/PagedTable.vue'
import { cellAt } from '../src/utils/map-geometry'
vi.mock('../src/utils/request', () => ({ default: { get: vi.fn() } }))
beforeEach(() => vi.clearAllMocks())
describe('paged requests', () => {
  it('ignores a late response even if the transport does not honour abort', async () => {
    const pending = []
    request.get.mockImplementation(
      (url, options) => new Promise((resolve) => pending.push({ resolve, options })),
    )
    const scope = effectScope()
    const query = scope.run(() => usePagedQuery('/records', { status: 0 }))
    const first = query.load()
    const second = query.load(true)
    expect(pending[0].options.signal.aborted).toBe(true)
    expect(pending[1].options.params.status).toBe(0)
    pending[1].resolve({ data: { total: 1, items: [{ id: 2 }] } })
    await second
    pending[0].resolve({ data: { total: 1, items: [{ id: 1 }] } })
    await first
    expect(query.rows.value).toEqual([{ id: 2 }])
    scope.stop()
  })
  it('does not apply results after its page is disposed', async () => {
    let resolve
    request.get.mockImplementation(() => new Promise((r) => (resolve = r)))
    const scope = effectScope()
    const query = scope.run(() => usePagedQuery('/records'))
    const call = query.load()
    scope.stop()
    resolve({ data: { total: 1, items: [{ id: 1 }] } })
    await call
    expect(query.rows.value).toEqual([])
  })
  it('moves back to the final valid page after deletion', async () => {
    request.get
      .mockResolvedValueOnce({ data: { total: 20, items: [] } })
      .mockResolvedValueOnce({ data: { total: 20, items: [{ id: 20 }] } })
    const scope = effectScope()
    const query = scope.run(() => usePagedQuery('/records'))
    query.pagination.pageNum = 2
    await query.load()
    expect(query.pagination.pageNum).toBe(1)
    expect(query.rows.value[0].id).toBe(20)
    scope.stop()
  })
})
it('maps clicks correctly after zooming and panning', () => {
  expect(cellAt(222, 186, { left: 10, top: 20 }, { x: 38, y: 50 }, 0.5)).toEqual({ col: 7, row: 5 })
})
it('shows a retry action and emits page changes', async () => {
  const wrapper = mount(PagedTable, {
    props: {
      rows: [],
      total: 100,
      loading: false,
      error: '加载失败',
      pagination: { pageNum: 1, pageSize: 20 },
    },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElAlert: { props: ['title'], template: '<section role="alert">{{title}}<slot/></section>' },
        ElButton: {
          emits: ['click'],
          template: '<button @click="$emit(\'click\')"><slot/></button>',
        },
        ElTable: { template: '<div><slot/></div>' },
        ElPagination: { template: '<button @click="$emit(\'current-change\',2)">下一页</button>' },
      },
    },
  })
  expect(wrapper.get('[role="alert"]').text()).toContain('加载失败')
  await wrapper.get('[role="alert"] button').trigger('click')
  expect(wrapper.emitted('reload')).toHaveLength(1)
  await wrapper.findAll('button').at(-1).trigger('click')
  expect(wrapper.emitted('page')[0]).toEqual([2])
})
