<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { CELL, cellAt, coordinateKey } from '@/utils/map-geometry'
const props = defineProps({
  spaces: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] },
  multiple: Boolean,
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue'])
const canvas = ref(),
  holder = ref(),
  scale = ref(1),
  offset = ref({ x: 22, y: 22 }),
  width = ref(800),
  height = 420
let observer,
  frame,
  down,
  dragged = false
const index = computed(
  () => new Map(props.spaces.map((s) => [coordinateKey(s.spaces_row, s.spaces_col), s])),
)
const selected = computed(() => new Set(props.modelValue.map((s) => s.id)))
const colors = {
  0: ['#ecfdf5', '#059669'],
  1: ['#fff1f2', '#dc4545'],
  2: ['#f1f5f9', '#94a3b8'],
  3: ['#334155', '#94a3b8'],
  4: ['#eff6ff', '#2563eb'],
}
function draw() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(devicePixelRatio || 1, 2)
  el.width = width.value * dpr
  el.height = height * dpr
  const ctx = el.getContext('2d')
  ctx.scale(dpr, dpr)
  ctx.fillStyle = '#f8fafc'
  ctx.fillRect(0, 0, width.value, height)
  ctx.save()
  ctx.translate(offset.value.x, offset.value.y)
  ctx.scale(scale.value, scale.value)
  const minCol = Math.max(1, Math.floor(-offset.value.x / (CELL * scale.value))),
    maxCol = Math.ceil((width.value - offset.value.x) / (CELL * scale.value)) + 1,
    minRow = Math.max(1, Math.floor(-offset.value.y / (CELL * scale.value))),
    maxRow = Math.ceil((height - offset.value.y) / (CELL * scale.value)) + 1
  for (let row = minRow; row <= maxRow; row++)
    for (let col = minCol; col <= maxCol; col++) {
      const s = index.value.get(coordinateKey(row, col))
      if (!s) continue
      const x = (col - 1) * CELL,
        y = (row - 1) * CELL
      const [bg, fg] = colors[s.spaces_flag] || colors[2]
      ctx.fillStyle = bg
      ctx.strokeStyle = selected.value.has(s.id) ? '#2563eb' : '#dce5ee'
      ctx.lineWidth = selected.value.has(s.id) ? 2.5 : 1
      ctx.beginPath()
      ctx.roundRect(x + 3, y + 3, CELL - 6, CELL - 6, 7)
      ctx.fill()
      ctx.stroke()
      ctx.fillStyle = fg
      ctx.textAlign = 'center'
      ctx.font = '600 13px sans-serif'
      ctx.fillText(
        s.spaces_flag === 4
          ? '出入口'
          : s.spaces_flag === 3
            ? '墙'
            : s.spaces_flag === 2
              ? '·'
              : s.spaces_name,
        x + CELL / 2,
        y + CELL / 2 + 4,
      )
    }
  ctx.restore()
}
function schedule() {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(draw)
}
function zoom(delta) {
  scale.value = Math.min(2, Math.max(0.35, +(scale.value + delta).toFixed(2)))
  schedule()
}
function pointerDown(e) {
  if (e.button !== 0) return
  down = { x: e.clientX, y: e.clientY, ox: offset.value.x, oy: offset.value.y }
  dragged = false
  canvas.value.setPointerCapture(e.pointerId)
}
function pointerMove(e) {
  if (!down) return
  const dx = e.clientX - down.x,
    dy = e.clientY - down.y
  if (Math.abs(dx) + Math.abs(dy) > 4) dragged = true
  if (dragged) {
    offset.value = { x: down.ox + dx, y: down.oy + dy }
    schedule()
  }
}
function pointerUp(e) {
  if (!down) return
  if (!dragged && !props.disabled) {
    const point = cellAt(
      e.clientX,
      e.clientY,
      canvas.value.getBoundingClientRect(),
      offset.value,
      scale.value,
    )
    const space = index.value.get(coordinateKey(point.row, point.col))
    if (space && (props.multiple || space.spaces_flag === 0)) {
      const exists = selected.value.has(space.id)
      emit(
        'update:modelValue',
        props.multiple
          ? exists
            ? props.modelValue.filter((s) => s.id !== space.id)
            : [...props.modelValue, space]
          : exists
            ? []
            : [space],
      )
    }
  }
  down = null
}
function reset() {
  offset.value = { x: 22, y: 22 }
  scale.value = 1
  schedule()
}
watch(() => props.spaces, schedule, { deep: false })
watch(() => props.modelValue, schedule, { deep: false })
onMounted(() => {
  observer = new ResizeObserver((entries) => {
    width.value = Math.max(1, entries[0].contentRect.width)
    schedule()
  })
  observer.observe(holder.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(frame)
})
</script>
<template>
  <div class="map-panel">
    <div class="map-toolbar">
      <div class="map-legend">
        <span v-for="(color, key) in colors" :key="key"
          ><i :style="{ background: color[1] }" />{{
            ['空闲', '占用', '道路', '墙体', '出入口'][key]
          }}</span
        >
      </div>
      <div>
        <el-button size="small" aria-label="缩小地图" @click="zoom(-0.1)">−</el-button
        ><span class="zoom-label">{{ Math.round(scale * 100) }}%</span
        ><el-button size="small" aria-label="放大地图" @click="zoom(0.1)">＋</el-button
        ><el-button size="small" @click="reset">复位</el-button>
      </div>
    </div>
    <div ref="holder" class="map-viewport">
      <canvas
        ref="canvas"
        :style="{ width: '100%', height: height + 'px' }"
        aria-label="停车场车位地图，拖动可移动视图；也可使用下方列表选择车位"
        role="img"
        @pointerdown="pointerDown"
        @pointermove="pointerMove"
        @pointerup="pointerUp"
        @pointercancel="down = null"
        @wheel.prevent="zoom($event.deltaY > 0 ? -0.05 : 0.05)"
      />
      <div v-if="!spaces.length" class="map-empty">当前楼层暂无车位</div>
    </div>
    <p class="map-hint">
      拖动地图平移，滚轮缩放。{{
        multiple
          ? '支持选择多个位置，已预约或在场车位不能编辑或删除。'
          : '选择绿色空闲车位后预约，最终结果以服务端确认为准。'
      }}
    </p>
  </div>
</template>
<style scoped>
.map-panel {
  border: 1px solid #e8edf4;
  border-radius: 12px;
  overflow: hidden;
  background: white;
}
.map-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  flex-wrap: wrap;
}
.map-legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #778398;
  flex-wrap: wrap;
}
.map-legend span {
  display: flex;
  align-items: center;
  gap: 5px;
}
.map-legend i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.map-viewport {
  width: 100%;
  position: relative;
  overflow: hidden;
  touch-action: none;
}
.map-viewport canvas {
  display: block;
  cursor: grab;
}
.map-viewport canvas:active {
  cursor: grabbing;
}
.map-empty {
  position: absolute;
  top: 45%;
  width: 100%;
  text-align: center;
  color: #94a3b8;
  pointer-events: none;
}
.map-hint {
  padding: 14px 18px;
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.7;
}
.zoom-label {
  display: inline-block;
  width: 50px;
  text-align: center;
  color: #778398;
  font-size: 12px;
}
</style>
