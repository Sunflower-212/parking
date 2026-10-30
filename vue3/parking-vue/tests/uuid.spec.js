import { webcrypto } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { randomUUID } from '../src/utils/uuid'
import { changes, generateCells } from '../src/utils/layout-draft'

afterEach(() => vi.unstubAllGlobals())

describe('UUID compatibility', () => {
  it('uses the native API when available', () => {
    const native = vi.fn(() => 'native-uuid')
    vi.stubGlobal('crypto', { randomUUID: native })
    expect(randomUUID()).toBe('native-uuid')
    expect(native).toHaveBeenCalledOnce()
  })

  it('generates distinct UUID v4 values without randomUUID on HTTP', () => {
    vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) })
    const ids = Array.from({ length: 1000 }, () => randomUUID())
    expect(new Set(ids).size).toBe(1000)
    for (const id of ids)
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    const cells = generateCells(
      { row: 1, col: 1, rows: 7, cols: 5, start: 1, prefix: 'A', order: 'row' },
      [],
    )
    expect(new Set(cells.map((cell) => cell.id)).size).toBe(35)
    expect(changes([], cells).creates).toHaveLength(35)
  })

  it('gives an actionable error when no secure random source exists', () => {
    vi.stubGlobal('crypto', undefined)
    expect(() => randomUUID()).toThrow('使用 HTTPS 访问')
  })
})
