export const CELL = 58
// 先减视口位置与平移，再除缩放后的格宽；行列从 1 开始，边界外结果由坐标索引自然排除。
export function cellAt(clientX, clientY, rect, offset, scale) {
  return {
    col: Math.floor((clientX - rect.left - offset.x) / (CELL * scale)) + 1,
    row: Math.floor((clientY - rect.top - offset.y) / (CELL * scale)) + 1,
  }
}
export function coordinateKey(row, col) {
  return `${row}:${col}`
}
export function zoomAt(point, offset, scale, nextScale) {
  return {
    x: point.x - ((point.x - offset.x) / scale) * nextScale,
    y: point.y - ((point.y - offset.y) / scale) * nextScale,
  }
}
export function fitView(spaces, width, height) {
  const maxCol = Math.max(4, ...spaces.map((s) => s.spaces_col)),
    maxRow = Math.max(3, ...spaces.map((s) => s.spaces_row))
  const scale = Math.max(
    0.02,
    Math.min(1.3, (width - 48) / (maxCol * CELL), (height - 48) / (maxRow * CELL)),
  )
  return {
    scale,
    offset: { x: (width - maxCol * CELL * scale) / 2, y: (height - maxRow * CELL * scale) / 2 },
  }
}
export function rectangleCells(a, b) {
  const cells = []
  for (
    let row = Math.max(1, Math.min(a.row, b.row));
    row <= Math.min(100, Math.max(a.row, b.row));
    row++
  )
    for (
      let col = Math.max(1, Math.min(a.col, b.col));
      col <= Math.min(100, Math.max(a.col, b.col));
      col++
    )
      cells.push({ row, col })
  return cells
}
