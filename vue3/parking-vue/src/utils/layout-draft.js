export const kind = (cell) => (cell.spaces_flag === 1 ? 0 : cell.spaces_flag)
export const keyAt = (cell) => `${cell.spaces_row}:${cell.spaces_col}`
export const editable = (cell) => cell && cell.editable !== false && cell.spaces_flag !== 1
export function changes(base, rows) {
  const original = new Map(base.map((c) => [c.id, c])),
    current = new Map(rows.map((c) => [c.id, c]))
  const creates = [],
    updates = [],
    deleteIds = []
  for (const c of rows) {
    const old = original.get(c.id)
    if (!old && !c._deleted)
      creates.push({
        clientId: c.id,
        name: c.spaces_name,
        row: c.spaces_row,
        col: c.spaces_col,
        type: kind(c),
      })
    else if (old && !c._deleted && (old.spaces_name !== c.spaces_name || kind(old) !== kind(c)))
      updates.push({ id: c.id, name: c.spaces_name, type: kind(c) })
  }
  for (const old of base)
    if (!current.has(old.id) || current.get(old.id)._deleted) deleteIds.push(old.id)
  return { creates, updates, deleteIds }
}
export function validateDraft(base, rows) {
  const active = rows.filter((c) => !c._deleted),
    coordinates = new Set()
  for (const c of active) {
    if (
      !Number.isInteger(c.spaces_row) ||
      !Number.isInteger(c.spaces_col) ||
      c.spaces_row < 1 ||
      c.spaces_row > 100 ||
      c.spaces_col < 1 ||
      c.spaces_col > 100
    )
      return '坐标必须在 1～100 行列以内'
    if (!c.spaces_name?.trim() || c.spaces_name.length > 10 || ![0, 2, 3, 4].includes(kind(c)))
      return '名称需为 1～10 个字符，类型无效'
    if (coordinates.has(keyAt(c))) return '位置坐标重复'
    coordinates.add(keyAt(c))
  }
  const delta = changes(base, rows)
  if (delta.creates.length + delta.updates.length + delta.deleteIds.length > 1000)
    return '一次最多保存 1,000 个位置修改，请缩小本次编辑范围'
  return ''
}
export function newCell(row, col, type, name) {
  return {
    id: `new-${crypto.randomUUID()}`,
    spaces_row: row,
    spaces_col: col,
    spaces_flag: type,
    spaces_name:
      name ||
      (type === 0
        ? `A${row}${String(col).padStart(2, '0')}`
        : `${{ 2: '道', 3: '墙', 4: '入口' }[type]}${row}-${col}`),
    editable: true,
  }
}
export function generateCells(options, existing) {
  const { row, col, rows, cols, start, prefix, order } = options
  if (
    ![row, col, rows, cols, start].every(Number.isInteger) ||
    row < 1 ||
    col < 1 ||
    rows < 1 ||
    cols < 1 ||
    start < 0 ||
    rows * cols > 1000 ||
    row + rows - 1 > 100 ||
    col + cols - 1 > 100
  )
    throw new Error('生成范围越界或超过 1,000 个车位')
  const occupied = new Set(existing.map(keyAt)),
    result = []
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < cols; c++) {
      if (occupied.has(`${row + r}:${col + c}`))
        throw new Error(`${row + r} 行 ${col + c} 列已有位置，不能覆盖`)
      const n = start + (order === 'column' ? c * rows + r : r * cols + c)
      const name = `${prefix}${String(n).padStart(3, '0')}`
      if (!name.trim() || name.length > 10) throw new Error('生成的名称不能超过 10 个字符')
      result.push(newCell(row + r, col + c, 0, name))
    }
  return result
}
// History stores changed cells only, not fifty copies of a 10,000-cell floor.
export function createHistory(initial = []) {
  let value = initial,
    undo = [],
    redo = []
  const apply = (patch) => {
    const next = new Map(value.map((c) => [c.id, c]))
    for (const [id, c] of patch) c ? next.set(id, c) : next.delete(id)
    value = [...next.values()]
    return value
  }
  return {
    get value() {
      return value
    },
    get canUndo() {
      return undo.length > 0
    },
    get canRedo() {
      return redo.length > 0
    },
    commit(next) {
      const a = new Map(value.map((c) => [c.id, c])),
        b = new Map(next.map((c) => [c.id, c])),
        before = [],
        after = []
      for (const id of new Set([...a.keys(), ...b.keys()]))
        if (JSON.stringify(a.get(id)) !== JSON.stringify(b.get(id))) {
          before.push([id, a.get(id)])
          after.push([id, b.get(id)])
        }
      if (before.length) {
        undo.push({ before, after })
        if (undo.length > 50) undo.shift()
        redo = []
        value = next
      }
      return value
    },
    undo() {
      const patch = undo.pop()
      if (patch) {
        redo.push(patch)
        apply(patch.before)
      }
      return value
    },
    redo() {
      const patch = redo.pop()
      if (patch) {
        undo.push(patch)
        apply(patch.after)
      }
      return value
    },
  }
}
