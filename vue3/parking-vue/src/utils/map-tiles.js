import { coordinateKey } from './map-geometry'

// 上、右、下、左分别占一位，共 16 种形状；只按当前楼层的有效草稿连接。
export const DIRECTIONS = [
  { bit: 1, row: -1, col: 0, x: 0.5, y: 0 },
  { bit: 2, row: 0, col: 1, x: 1, y: 0.5 },
  { bit: 4, row: 1, col: 0, x: 0.5, y: 1 },
  { bit: 8, row: 0, col: -1, x: 0, y: 0.5 },
]

export function connectionMask(index, cell, types) {
  if (cell._deleted) return 0
  let mask = 0
  for (const direction of DIRECTIONS) {
    const neighbor = index.get(
      coordinateKey(cell.spaces_row + direction.row, cell.spaces_col + direction.col),
    )
    if (neighbor && !neighbor._deleted && types.includes(neighbor.spaces_flag))
      mask |= direction.bit
  }
  return mask
}

export function buildConnections(index) {
  const result = new Map()
  for (const [key, cell] of index) {
    if (cell.spaces_flag === 2 || cell.spaces_flag === 3)
      result.set(key, connectionMask(index, cell, [cell.spaces_flag, 4]))
    else if (cell.spaces_flag === 4) result.set(key, connectionMask(index, cell, [2]))
  }
  return result
}

export function entranceSymbol(mask) {
  if (mask && !(mask & 5)) return '↔'
  if (mask && !(mask & 10)) return '↕'
  return '▣'
}

export function drawRoad(ctx, mask, x, y, size, scale) {
  ctx.save()
  ctx.fillStyle = '#dfe7f0'
  // 连通边向同色邻格重叠半个屏幕像素，消除非整数缩放时的抗锯齿细缝。
  const overlap = 0.5 / scale,
    left = mask & 8 ? overlap : 0,
    top = mask & 1 ? overlap : 0
  ctx.fillRect(
    x - left,
    y - top,
    size + left + (mask & 2 ? overlap : 0),
    size + top + (mask & 4 ? overlap : 0),
  )
  const links = DIRECTIONS.filter((d) => mask & d.bit)
  if (links.length && size * scale >= 18) {
    ctx.strokeStyle = '#fff'
    ctx.lineWidth = 1.5
    // 每格恰好四个虚线周期，直道跨格时保持相位一致。
    ctx.setLineDash([size / 8, size / 8])
    ctx.lineDashOffset = 0
    ctx.beginPath()
    if (links.length === 2) {
      const [a, b] = mask === 10 ? [DIRECTIONS[3], DIRECTIONS[1]] : links
      ctx.moveTo(x + a.x * size, y + a.y * size)
      if (mask === 5 || mask === 10) ctx.lineTo(x + b.x * size, y + b.y * size)
      else ctx.quadraticCurveTo(x + size / 2, y + size / 2, x + b.x * size, y + b.y * size)
    } else {
      for (const d of links) {
        ctx.moveTo(x + d.x * size, y + d.y * size)
        const reach = links.length >= 3 ? 0.44 : 0
        ctx.lineTo(x + (0.5 + (d.x - 0.5) * reach) * size, y + (0.5 + (d.y - 0.5) * reach) * size)
      }
    }
    ctx.stroke()
  }
  ctx.restore()
}

export function drawWall(ctx, mask, x, y, size) {
  ctx.save()
  const centerX = x + size / 2,
    centerY = y + size / 2,
    thickness = size * (18 / 58),
    links = DIRECTIONS.filter((d) => mask & d.bit)
  ctx.fillStyle = '#475569'
  ctx.strokeStyle = '#475569'
  ctx.lineWidth = thickness
  ctx.lineJoin = 'round'
  ctx.lineCap = 'butt'
  ctx.setLineDash([])
  ctx.beginPath()
  if (links.length === 2) {
    const [a, b] = links
    ctx.moveTo(x + a.x * size, y + a.y * size)
    ctx.lineTo(centerX, centerY)
    ctx.lineTo(x + b.x * size, y + b.y * size)
  } else {
    for (const d of links) {
      ctx.moveTo(centerX, centerY)
      ctx.lineTo(x + d.x * size, y + d.y * size)
    }
  }
  ctx.stroke()
  if (links.length !== 2) {
    const length = links.length ? thickness : size * 0.6
    ctx.beginPath()
    ctx.roundRect(centerX - thickness / 2, centerY - length / 2, thickness, length, 3)
    ctx.fill()
  }
  ctx.restore()
}
