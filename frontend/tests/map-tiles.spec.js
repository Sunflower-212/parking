import { describe, expect, it, vi } from 'vitest'
import {
  DIRECTIONS,
  buildConnections,
  drawRoad,
  drawWall,
  entranceSymbol,
} from '../src/utils/map-tiles'
import { coordinateKey, CELL } from '../src/utils/map-geometry'
import { createHistory } from '../src/utils/layout-draft'

const cell = (row, col, type, extra = {}) => ({
  id: `${row}:${col}`,
  spaces_row: row,
  spaces_col: col,
  spaces_flag: type,
  ...extra,
})
const indexOf = (cells) => new Map(cells.map((c) => [coordinateKey(c.spaces_row, c.spaces_col), c]))
function fixture(mask, type) {
  return [
    cell(2, 2, type),
    ...DIRECTIONS.filter((d) => mask & d.bit).map((d) => cell(2 + d.row, 2 + d.col, type)),
  ]
}
function context() {
  return Object.fromEntries(
    [
      'save',
      'restore',
      'fillRect',
      'setLineDash',
      'beginPath',
      'moveTo',
      'lineTo',
      'quadraticCurveTo',
      'stroke',
      'roundRect',
      'fill',
    ].map((name) => [name, vi.fn()]),
  )
}

describe.each([2, 3])('type %i four-direction connections', (type) => {
  it.each(Array.from({ length: 16 }, (_, i) => i))('resolves mask %i', (mask) => {
    expect(buildConnections(indexOf(fixture(mask, type))).get('2:2')).toBe(mask)
  })
  it('ignores diagonals, other infrastructure, spaces and deleted neighbors', () => {
    const cells = [
      cell(2, 2, type),
      cell(1, 1, type),
      cell(3, 3, type),
      cell(1, 2, type === 2 ? 3 : 2),
      cell(2, 3, 0),
      cell(3, 2, 1),
      cell(2, 1, type, { _deleted: true }),
    ]
    expect(buildConnections(indexOf(cells)).get('2:2')).toBe(0)
  })
  it('connects to an entrance but not through a deleted cell', () => {
    const cells = [cell(1, 1, type), cell(1, 2, 4)]
    expect(buildConnections(indexOf(cells)).get('1:1')).toBe(2)
    cells[0]._deleted = true
    expect(buildConnections(indexOf(cells)).get('1:1')).toBe(0)
  })
})

it('updates connections after a stroke, type change, deletion, undo and redo', () => {
  const initial = fixture(10, 2),
    history = createHistory(initial)
  const mask = () => buildConnections(indexOf(history.value)).get('2:2')
  expect(mask()).toBe(10)
  history.commit([...initial, cell(3, 2, 2)])
  expect(mask()).toBe(14)
  history.commit(history.value.map((c) => (c.id === '2:3' ? { ...c, _deleted: true } : c)))
  expect(mask()).toBe(12)
  history.undo()
  expect(mask()).toBe(14)
  history.redo()
  expect(mask()).toBe(12)
  history.commit(history.value.map((c) => (c.id === '3:2' ? { ...c, spaces_flag: 3 } : c)))
  expect(mask()).toBe(8)
})

it('chooses entrance symbols using roads only, without propagating walls through the entrance', () => {
  expect(entranceSymbol(2)).toBe('↔')
  expect(entranceSymbol(10)).toBe('↔')
  expect(entranceSymbol(1)).toBe('↕')
  expect(entranceSymbol(5)).toBe('↕')
  for (const mask of [0, 3, 7, 15]) expect(entranceSymbol(mask)).toBe('▣')
  const connections = buildConnections(
    indexOf([cell(1, 1, 3), cell(1, 2, 4), cell(1, 3, 3), cell(2, 2, 2)]),
  )
  expect(connections.get('1:1')).toBe(2)
  expect(connections.get('1:2')).toBe(4)
  expect(connections.get('1:3')).toBe(8)
  expect(connections.get('2:2')).toBe(1)
})

describe('vector drawing', () => {
  it.each([3, 6, 9, 12])('draws a curved road marking for corner %i', (mask) => {
    const ctx = context()
    drawRoad(ctx, mask, 0, 0, CELL, 1)
    expect(ctx.quadraticCurveTo).toHaveBeenCalledOnce()
    expect(ctx.lineTo).not.toHaveBeenCalled()
  })
  it.each([5, 10])('draws a full straight marking for mask %i', (mask) => {
    const ctx = context()
    drawRoad(ctx, mask, 0, 0, CELL, 1)
    expect(ctx.lineTo).toHaveBeenCalledOnce()
    expect(ctx.quadraticCurveTo).not.toHaveBeenCalled()
    expect(ctx.setLineDash).toHaveBeenCalledWith([CELL / 8, CELL / 8])
  })
  it.each([7, 11, 13, 14, 15])('leaves the center of junction %i clear', (mask) => {
    const ctx = context()
    drawRoad(ctx, mask, 0, 0, CELL, 1)
    expect(ctx.lineTo.mock.calls).toHaveLength(mask === 15 ? 4 : 3)
    for (const [x, y] of ctx.lineTo.mock.calls)
      expect(Math.hypot(x - CELL / 2, y - CELL / 2)).toBeGreaterThan(CELL * 0.2)
  })
  it('does not draw direction markings for isolated roads or at low zoom', () => {
    for (const [mask, scale] of [
      [0, 1],
      [15, 0.1],
    ]) {
      const ctx = context()
      drawRoad(ctx, mask, 0, 0, CELL, scale)
      expect(ctx.fillRect).toHaveBeenCalledOnce()
      expect(ctx.stroke).not.toHaveBeenCalled()
      expect(ctx.save).toHaveBeenCalledOnce()
      expect(ctx.restore).toHaveBeenCalledOnce()
    }
  })
  it('overlaps connected road edges only, preventing fractional zoom seams', () => {
    const ctx = context()
    drawRoad(ctx, 6, 10, 20, CELL, 0.5)
    expect(ctx.fillRect).toHaveBeenCalledWith(10, 20, CELL + 1, CELL + 1)
    const isolated = context()
    drawRoad(isolated, 0, 10, 20, CELL, 0.5)
    expect(isolated.fillRect).toHaveBeenCalledWith(10, 20, CELL, CELL)
  })
  it.each([3, 6, 9, 12])('joins both wall arms for corner %i at a common center', (mask) => {
    const ctx = context(),
      links = DIRECTIONS.filter((d) => mask & d.bit)
    drawWall(ctx, mask, 0, 0, CELL)
    expect(ctx.moveTo).toHaveBeenCalledWith(links[0].x * CELL, links[0].y * CELL)
    expect(ctx.lineTo.mock.calls).toEqual([
      [CELL / 2, CELL / 2],
      [links[1].x * CELL, links[1].y * CELL],
    ])
    expect(ctx.lineJoin).toBe('round')
    expect(ctx.lineWidth).toBe(18)
  })
  it('draws an isolated short wall without invented full-cell connections', () => {
    const ctx = context()
    drawWall(ctx, 0, 0, 0, CELL)
    expect(ctx.lineTo).not.toHaveBeenCalled()
    expect(ctx.roundRect).toHaveBeenCalledWith(20, CELL * 0.2, 18, CELL * 0.6, 3)
  })
})
