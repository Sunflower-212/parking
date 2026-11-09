import { describe, it, expect } from 'vitest'
import {
  createHistory,
  changes,
  generateCells,
  newCell,
  validateDraft,
} from '../src/utils/layout-draft'
import { zoomAt, fitView, cellAt, rectangleCells, CELL } from '../src/utils/map-geometry'
describe('map coordinates and draft', () => {
  it('keeps the world point under the pointer fixed while zooming', () => {
    const p = { x: 210, y: 130 },
      offset = { x: 30, y: 20 },
      next = zoomAt(p, offset, 0.5, 1.5)
    expect((p.x - next.x) / 1.5).toBe((p.x - offset.x) / 0.5)
    expect((p.y - next.y) / 1.5).toBe((p.y - offset.y) / 0.5)
  })
  it('fits a 100 by 100 map on a phone and still resolves the last cell', () => {
    const view = fitView([{ spaces_col: 100, spaces_row: 100 }], 343, 420)
    expect(100 * CELL * view.scale).toBeLessThanOrEqual(343 - 48)
    expect(
      cellAt(
        view.offset.x + 99.5 * CELL * view.scale,
        view.offset.y + 99.5 * CELL * view.scale,
        { left: 0, top: 0 },
        view.offset,
        view.scale,
      ),
    ).toEqual({ row: 100, col: 100 })
  })
  it('clamps reverse rectangle selections at map boundaries', () =>
    expect(rectangleCells({ row: 3, col: 2 }, { row: 0, col: 0 })).toHaveLength(6))
  it('generates numbering in both directions without overwriting any existing coordinate', () => {
    const opts = { row: 1, col: 1, rows: 2, cols: 3, start: 1, prefix: 'A', order: 'row' }
    expect(generateCells(opts, []).map((c) => c.spaces_name)).toEqual([
      'A001',
      'A002',
      'A003',
      'A004',
      'A005',
      'A006',
    ])
    expect(generateCells({ ...opts, order: 'column' }, []).map((c) => c.spaces_name)).toEqual([
      'A001',
      'A003',
      'A005',
      'A002',
      'A004',
      'A006',
    ])
    expect(() => generateCells(opts, [newCell(1, 2, 2)])).toThrow('不能覆盖')
    expect(() => generateCells({ ...opts, rows: 100, cols: 100 }, [])).toThrow('1,000')
    expect(() => generateCells({ ...opts, prefix: 'toolongprefix' }, [])).toThrow('10')
  })
  it('ignores occupancy changes but preserves IDs for edits and removals', () => {
    const base = [
      { ...newCell(1, 1, 0, 'A001'), id: 1 },
      { ...newCell(1, 2, 0, 'A002'), id: 2 },
    ]
    expect(changes(base, [{ ...base[0], spaces_flag: 1 }, base[1]])).toEqual({
      creates: [],
      updates: [],
      deleteIds: [],
    })
    const next = [
      { ...base[0], spaces_name: 'B001' },
      { ...base[1], _deleted: true },
      newCell(2, 1, 0),
    ]
    expect(changes(base, next)).toMatchObject({
      updates: [{ id: 1, name: 'B001', type: 0 }],
      deleteIds: [2],
    })
    expect(changes(base, next).creates).toHaveLength(1)
  })
  it('bounds validation and records a continuous stroke as one undo step', () => {
    const initial = [newCell(1, 1, 0)],
      history = createHistory(initial),
      drawn = [...initial, newCell(2, 1, 2), newCell(3, 1, 2)]
    history.commit(drawn)
    expect(history.undo()).toEqual(initial)
    expect(history.redo()).toEqual(drawn)
    history.undo()
    history.commit([...initial, newCell(4, 1, 4)])
    expect(history.canRedo).toBe(false)
    expect(
      validateDraft(
        [],
        Array.from({ length: 1001 }, (_, i) => newCell(Math.floor(i / 100) + 1, (i % 100) + 1, 0)),
      ),
    ).toContain('1,000')
    expect(validateDraft([], [newCell(101, 1, 0)])).toContain('1～100')
  })
  it('keeps only fifty reversible gestures', () => {
    const initial = [newCell(1, 1, 0, 'A')],
      h = createHistory(initial)
    for (let i = 1; i <= 55; i++) h.commit([{ ...initial[0], spaces_name: `A${i}` }])
    for (let i = 0; i < 50; i++) h.undo()
    expect(h.value[0].spaces_name).toBe('A5')
    expect(h.canUndo).toBe(false)
  })
})
