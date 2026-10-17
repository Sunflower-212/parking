export const CELL = 58
export function cellAt(clientX, clientY, rect, offset, scale) {
  return {
    col: Math.floor((clientX - rect.left - offset.x) / (CELL * scale)) + 1,
    row: Math.floor((clientY - rect.top - offset.y) / (CELL * scale)) + 1,
  }
}
export function coordinateKey(row, col) {
  return `${row}:${col}`
}
