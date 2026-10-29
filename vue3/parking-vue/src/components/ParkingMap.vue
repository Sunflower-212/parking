<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { CELL, cellAt, coordinateKey, zoomAt, fitView, rectangleCells } from '@/utils/map-geometry'
const props = defineProps({
  spaces: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] },
  multiple: Boolean,
  disabled: Boolean,
  editing: Boolean,
  tool: { type: String, default: 'select' },
  contextKey: String,
})
const emit = defineEmits(['update:modelValue', 'stroke'])
const canvas = ref(),
  holder = ref(),
  panel = ref(),
  scale = ref(1),
  offset = ref({ x: 24, y: 24 }),
  width = ref(800),
  height = ref(480),
  box = ref(null),
  fullscreen = ref(false)
const index = computed(
  () => new Map(props.spaces.map((s) => [coordinateKey(s.spaces_row, s.spaces_col), s])),
)
const selected = computed(() => new Set(props.modelValue.map((s) => s.id)))
const legend = [
  ['#10b981', '空闲'],
  ['#94a3b8', '不可用'],
  ['#2563eb', '已选择'],
  ['#cbd5e1', '道路'],
  ['#475569', '墙体'],
  ['#3b82f6', '出入口'],
]
let observer,
  frame,
  first = true,
  gesture,
  pointers = new Map(),
  pinch,
  stroke = new Map()
function schedule() {
  if (!frame)
    frame = requestAnimationFrame(() => {
      frame = null
      draw()
    })
}
function draw() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  if (el.width !== Math.round(width.value * dpr) || el.height !== Math.round(height.value * dpr)) {
    el.width = Math.round(width.value * dpr)
    el.height = Math.round(height.value * dpr)
  }
  const ctx = el.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.fillStyle = '#f3f6fa'
  ctx.fillRect(0, 0, width.value, height.value)
  ctx.save()
  ctx.translate(offset.value.x, offset.value.y)
  ctx.scale(scale.value, scale.value)
  const minCol = Math.max(1, Math.floor(-offset.value.x / (CELL * scale.value)) + 1),
    maxCol = Math.min(100, Math.ceil((width.value - offset.value.x) / (CELL * scale.value)))
  const minRow = Math.max(1, Math.floor(-offset.value.y / (CELL * scale.value)) + 1),
    maxRow = Math.min(100, Math.ceil((height.value - offset.value.y) / (CELL * scale.value)))
  if (props.editing && scale.value * CELL > 12) {
    ctx.strokeStyle = '#e0e7ef'
    ctx.lineWidth = 1 / scale.value
    ctx.beginPath()
    for (let c = minCol; c <= maxCol + 1; c++) {
      ctx.moveTo((c - 1) * CELL, (minRow - 1) * CELL)
      ctx.lineTo((c - 1) * CELL, maxRow * CELL)
    }
    for (let r = minRow; r <= maxRow + 1; r++) {
      ctx.moveTo((minCol - 1) * CELL, (r - 1) * CELL)
      ctx.lineTo(maxCol * CELL, (r - 1) * CELL)
    }
    ctx.stroke()
  }
  for (let row = minRow; row <= maxRow; row++)
    for (let col = minCol; col <= maxCol; col++) {
      const s = index.value.get(coordinateKey(row, col))
      if (!s) continue
      const x = (col - 1) * CELL,
        y = (row - 1) * CELL,
        chosen = selected.value.has(s.id),
        flag = s.spaces_flag,
        labels = scale.value * CELL >= 26
      ctx.globalAlpha = s._deleted ? 0.35 : 1
      ctx.setLineDash([])
      if (flag < 2) {
        ctx.fillStyle = chosen ? '#dbeafe' : flag === 0 ? '#e7f7f0' : '#e3e8ef'
        ctx.fillRect(x + 7, y + 4, CELL - 14, CELL - 8)
        ctx.strokeStyle = chosen ? '#2563eb' : flag === 0 ? '#34ad86' : '#a5b1c2'
        ctx.lineWidth = chosen ? 2.5 : 1.5
        ctx.beginPath()
        ctx.moveTo(x + 7, y + 9)
        ctx.lineTo(x + 7, y + CELL - 5)
        ctx.lineTo(x + CELL - 7, y + CELL - 5)
        ctx.lineTo(x + CELL - 7, y + 9)
        ctx.stroke()
        if (flag === 1) {
          ctx.fillStyle = '#b5c0ce'
          ctx.beginPath()
          ctx.roundRect(x + 20, y + 9, 18, 27, 5)
          ctx.fill()
        }
      } else if (flag === 2) {
        ctx.fillStyle = '#dfe7f0'
        ctx.fillRect(x, y, CELL, CELL)
        const horizontal = [col - 1, col + 1].some(
          (c) => index.value.get(coordinateKey(row, c))?.spaces_flag === 2,
        )
        ctx.strokeStyle = '#fff'
        ctx.lineWidth = 1.5
        ctx.setLineDash([7, 7])
        ctx.beginPath()
        if (horizontal) {
          ctx.moveTo(x, y + CELL / 2)
          ctx.lineTo(x + CELL, y + CELL / 2)
        } else {
          ctx.moveTo(x + CELL / 2, y)
          ctx.lineTo(x + CELL / 2, y + CELL)
        }
        ctx.stroke()
        ctx.setLineDash([])
      } else if (flag === 3) {
        ctx.fillStyle = '#475569'
        const horizontal = [col - 1, col + 1].some(
          (c) => index.value.get(coordinateKey(row, c))?.spaces_flag === 3,
        )
        ctx.fillRect(
          x + (horizontal ? 0 : 20),
          y + (horizontal ? 20 : 0),
          horizontal ? CELL : 18,
          horizontal ? 18 : CELL,
        )
      } else {
        ctx.fillStyle = '#e0ecff'
        ctx.fillRect(x + 2, y + 2, CELL - 4, CELL - 4)
        ctx.fillStyle = '#2563eb'
        ctx.font = 'bold 22px sans-serif'
        ctx.textAlign = 'center'
        if (labels) ctx.fillText('↔', x + CELL / 2, y + 28)
      }
      if (chosen && flag >= 2) {
        ctx.strokeStyle = '#2563eb'
        ctx.lineWidth = 2
        ctx.strokeRect(x + 2, y + 2, CELL - 4, CELL - 4)
      }
      if (labels && (flag < 2 || flag === 4)) {
        ctx.fillStyle = chosen ? '#1d4ed8' : flag === 0 ? '#15795f' : '#53647c'
        ctx.font = '600 10px sans-serif'
        ctx.textAlign = 'center'
        ctx.fillText(flag === 4 ? '出入口' : s.spaces_name, x + CELL / 2, y + 47, CELL - 12)
      }
      if (s._change || s._deleted) {
        ctx.strokeStyle = s._deleted ? '#ef4444' : s._change === 'new' ? '#10b981' : '#f59e0b'
        ctx.lineWidth = 2
        ctx.setLineDash([4, 3])
        ctx.strokeRect(x + 1, y + 1, CELL - 2, CELL - 2)
        ctx.setLineDash([])
      }
      ctx.globalAlpha = 1
    }
  if (box.value) {
    const { a, b } = box.value
    const x = (Math.min(a.col, b.col) - 1) * CELL,
      y = (Math.min(a.row, b.row) - 1) * CELL
    ctx.fillStyle = '#2563eb20'
    ctx.strokeStyle = '#2563eb'
    ctx.lineWidth = 1 / scale.value
    ctx.fillRect(x, y, (Math.abs(a.col - b.col) + 1) * CELL, (Math.abs(a.row - b.row) + 1) * CELL)
    ctx.strokeRect(x, y, (Math.abs(a.col - b.col) + 1) * CELL, (Math.abs(a.row - b.row) + 1) * CELL)
  }
  if (props.editing && stroke.size) {
    ctx.fillStyle = '#2563eb45'
    for (const c of stroke.values())
      ctx.fillRect((c.col - 1) * CELL + 2, (c.row - 1) * CELL + 2, CELL - 4, CELL - 4)
  }
  ctx.restore()
}
function local(e) {
  const r = canvas.value.getBoundingClientRect()
  return { x: e.clientX - r.left, y: e.clientY - r.top }
}
function cell(e) {
  return cellAt(
    e.clientX,
    e.clientY,
    canvas.value.getBoundingClientRect(),
    offset.value,
    scale.value,
  )
}
function zoom(delta, anchor = { x: width.value / 2, y: height.value / 2 }) {
  autoFit = false
  const next = Math.max(0.02, Math.min(3, scale.value * delta))
  offset.value = zoomAt(anchor, offset.value, scale.value, next)
  scale.value = next
  schedule()
}
function fit() {
  autoFit = true
  const view = fitView(props.spaces, width.value, height.value)
  scale.value = view.scale
  offset.value = view.offset
  schedule()
}
function focus(s) {
  autoFit = false
  scale.value = Math.max(0.8, scale.value)
  offset.value = {
    x: width.value / 2 - (s.spaces_col - 0.5) * CELL * scale.value,
    y: height.value / 2 - (s.spaces_row - 0.5) * CELL * scale.value,
  }
  schedule()
}
function addStroke(a, b) {
  const steps = Math.max(Math.abs(b.row - a.row), Math.abs(b.col - a.col), 1)
  for (let i = 0; i <= steps; i++) {
    const row = Math.round(a.row + ((b.row - a.row) * i) / steps),
      col = Math.round(a.col + ((b.col - a.col) * i) / steps)
    if (row >= 1 && row <= 100 && col >= 1 && col <= 100) stroke.set(`${row}:${col}`, { row, col })
  }
}
function down(e) {
  if (e.button !== 0) return
  canvas.value.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, local(e))
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinch = {
      distance: Math.hypot(a.x - b.x, a.y - b.y),
      center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
    }
    gesture = null
    stroke.clear()
    box.value = null
    schedule()
    return
  }
  const point = cell(e)
  gesture = {
    start: local(e),
    origin: { ...offset.value },
    cell: point,
    last: point,
    moved: false,
    add: e.shiftKey || e.ctrlKey || e.metaKey,
  }
  if (props.editing && !props.disabled && !['select', 'pan'].includes(props.tool)) {
    stroke.clear()
    addStroke(point, point)
    schedule()
  }
}
function move(e) {
  if (!pointers.has(e.pointerId)) return
  pointers.set(e.pointerId, local(e))
  if (pinch && pointers.size === 2) {
    const [a, b] = [...pointers.values()],
      distance = Math.hypot(a.x - b.x, a.y - b.y),
      center = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    if (pinch.distance > 0) {
      zoom(distance / pinch.distance, pinch.center)
      offset.value = {
        x: offset.value.x + center.x - pinch.center.x,
        y: offset.value.y + center.y - pinch.center.y,
      }
    }
    pinch = { distance, center }
    schedule()
    return
  }
  if (!gesture) return
  const p = local(e),
    c = cell(e),
    dx = p.x - gesture.start.x,
    dy = p.y - gesture.start.y
  if (Math.hypot(dx, dy) > 4) gesture.moved = true
  if (props.editing && !props.disabled && props.tool === 'select') {
    box.value = { a: gesture.cell, b: c }
    schedule()
  } else if (props.editing && !props.disabled && props.tool !== 'pan') {
    addStroke(gesture.last, c)
    gesture.last = c
    schedule()
  } else if (gesture.moved) {
    autoFit = false
    offset.value = { x: gesture.origin.x + dx, y: gesture.origin.y + dy }
    schedule()
  }
}
function up(e) {
  pointers.delete(e.pointerId)
  if (pinch) {
    if (pointers.size === 0) pinch = null
    gesture = null
    return
  }
  if (!gesture) return
  if (!props.disabled) {
    if (props.editing && !['select', 'pan'].includes(props.tool))
      emit('stroke', { tool: props.tool, cells: [...stroke.values()] })
    else if (props.editing && props.tool === 'select' && gesture.moved) {
      const ids = new Set(
        rectangleCells(gesture.cell, cell(e)).map((p) => coordinateKey(p.row, p.col)),
      )
      const found = props.spaces.filter(
        (s) => !s._deleted && ids.has(coordinateKey(s.spaces_row, s.spaces_col)),
      )
      emit(
        'update:modelValue',
        gesture.add
          ? [...new Map([...props.modelValue, ...found].map((s) => [s.id, s])).values()]
          : found,
      )
    } else if (!gesture.moved && (!props.editing || props.tool === 'select')) {
      const p = cell(e),
        s = index.value.get(coordinateKey(p.row, p.col))
      if (s && !s._deleted) {
        const exists = selected.value.has(s.id)
        emit(
          'update:modelValue',
          props.multiple
            ? exists
              ? props.modelValue.filter((v) => v.id !== s.id)
              : [...props.modelValue, s]
            : exists
              ? []
              : [s],
        )
      } else emit('update:modelValue', [])
    }
  }
  gesture = null
  stroke.clear()
  box.value = null
  schedule()
}
function cancelPointer() {
  pointers.clear()
  pinch = null
  gesture = null
  stroke.clear()
  box.value = null
  schedule()
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement === panel.value) await document.exitFullscreen()
    else await panel.value.requestFullscreen()
  } catch {
    fullscreen.value = !fullscreen.value
    schedule()
  }
}
function fullChanged() {
  fullscreen.value = document.fullscreenElement === panel.value
}
watch(
  () => props.spaces,
  () => {
    if (first && props.spaces.length) {
      first = false
      fit()
    } else schedule()
  },
)
watch(() => props.modelValue, schedule)
watch(
  () => props.contextKey,
  () => {
    first = true
    cancelPointer()
    fit()
  },
)
watch(() => props.editing, schedule)
let autoFit = true
onMounted(() => {
  observer = new ResizeObserver((entries) => {
    const oldWidth = width.value,
      oldHeight = height.value
    width.value = Math.max(1, entries[0].contentRect.width)
    height.value = Math.max(240, entries[0].contentRect.height)
    if (first || autoFit) fit()
    else {
      offset.value = {
        x: offset.value.x + (width.value - oldWidth) / 2,
        y: offset.value.y + (height.value - oldHeight) / 2,
      }
      schedule()
    }
  })
  observer.observe(holder.value)
  document.addEventListener('fullscreenchange', fullChanged)
  schedule()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(frame)
  document.removeEventListener('fullscreenchange', fullChanged)
})
defineExpose({ fit, focus })
</script>
<template>
  <section ref="panel" class="parking-map" :class="{ 'map-full': fullscreen }">
    <div class="map-top">
      <div class="map-legend">
        <span v-for="[color, label] in legend" :key="label"
          ><i :style="{ background: color }" />{{ label }}</span
        >
      </div>
      <el-button size="small" @click="toggleFullscreen">{{
        fullscreen ? '退出全屏' : '全屏地图'
      }}</el-button>
    </div>
    <div ref="holder" class="map-viewport">
      <canvas
        ref="canvas"
        :style="{
          width: '100%',
          height: '100%',
          cursor: editing && tool !== 'pan' ? 'crosshair' : 'grab',
        }"
        role="img"
        aria-label="停车场二维地图，可拖动缩放，也可使用位置列表进行键盘操作"
        @pointerdown="down"
        @pointermove="move"
        @pointerup="up"
        @pointercancel="cancelPointer"
        @wheel.prevent="zoom($event.deltaY > 0 ? 0.9 : 1.1, local($event))"
      />
      <div v-if="!spaces.length" class="map-empty">
        <strong>{{ editing ? '从第一个车位开始' : '这一层还没有位置' }}</strong
        ><span>{{
          editing ? '选择左侧工具，在网格上绘制，或使用批量生成' : '切换楼层，或等待管理员配置地图'
        }}</span>
      </div>
      <div class="map-controls">
        <el-button aria-label="缩小地图" @click="zoom(0.8)">−</el-button
        ><span>{{ Math.round(scale * 100) }}%</span
        ><el-button aria-label="放大地图" @click="zoom(1.25)">＋</el-button
        ><el-button @click="fit">适配全图</el-button>
      </div>
    </div>
    <div class="map-foot">
      <span
        ><i class="live-dot" />
        {{ editing ? '编辑预览 · 尚未影响正式地图' : '实时状态 · 以服务器确认为准' }}</span
      ><span>{{ editing ? '框选多个位置 · Shift 追加选择' : '拖动平移 · 滚轮或双指缩放' }}</span>
    </div>
  </section>
</template>
<style scoped>
.parking-map {
  background: #fff;
  border: 1px solid #e1e8f1;
  border-radius: 18px;
  overflow: hidden;
  min-width: 0;
}
.map-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
}
.map-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
}
.map-legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.map-legend i {
  width: 8px;
  height: 8px;
  border-radius: 3px;
}
.map-viewport {
  position: relative;
  height: clamp(400px, 60vh, 640px);
  overflow: hidden;
  touch-action: none;
}
.map-viewport canvas {
  display: block;
}
.map-controls {
  position: absolute;
  right: 16px;
  bottom: 16px;
  display: flex;
  align-items: center;
  background: #ffffffed;
  padding: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 4px 20px #263b5720;
}
.map-controls span {
  width: 44px;
  text-align: center;
  font-size: 12px;
  color: #64748b;
}
.map-controls .el-button {
  margin-left: 0;
  border: 0;
}
.map-foot {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 20px;
  color: #8291a5;
  font-size: 12px;
}
.live-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #10b981;
  border-radius: 50%;
  margin-right: 6px;
}
.map-empty {
  position: absolute;
  top: 38%;
  left: 5%;
  width: 90%;
  text-align: center;
  pointer-events: none;
  color: #8394aa;
  display: grid;
  gap: 10px;
}
.map-empty strong {
  font-size: 20px;
  color: #455a76;
}
.map-empty span {
  font-size: 13px;
}
.parking-map:fullscreen,
.map-full {
  border-radius: 0;
  background: #fff;
}
.parking-map:fullscreen .map-viewport {
  height: calc(100vh - 140px);
}
.map-full:not(:fullscreen) {
  position: fixed;
  inset: 0;
  z-index: 2100;
}
.map-full:not(:fullscreen) .map-viewport {
  height: calc(100dvh - 150px);
}
@media (max-width: 600px) {
  .map-top {
    padding: 12px;
  }
  .map-legend {
    gap: 8px;
    font-size: 11px;
  }
  .map-viewport {
    height: 420px;
  }
  .map-foot {
    padding: 12px;
  }
  .map-controls {
    right: 10px;
    bottom: 10px;
  }
}
</style>
