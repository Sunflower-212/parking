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
