<script setup>
import { ref, shallowRef, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import PageShell from './PageShell.vue'
import ParkingMap from './ParkingMap.vue'
import StatusTag from './StatusTag.vue'
import { dateTime } from '@/utils/format'
import {
  kind,
  keyAt,
  changes,
  validateDraft,
  generateCells,
  newCell,
  createHistory,
} from '@/utils/layout-draft'
const props = defineProps({ admin: Boolean })
const parks = ref([]),
  parkId = ref(),
  floor = ref(1),
  server = shallowRef({ revision: '', items: [] }),
  active = ref([]),
  selected = ref([]),
  loading = ref(false),
  refreshing = ref(false),
  error = ref(''),
  busy = ref(false),
  updated = ref(null),
  map = ref()
const editing = ref(false),
  base = shallowRef([]),
  draft = shallowRef([]),
  baseRevision = ref(''),
  conflict = ref(''),
  conflictIds = ref([]),
  uncertain = ref(false),
  tool = ref('select'),
  historyVersion = ref(0)
const filter = ref(''),
  onlyFree = ref(false),
  listPage = ref(1),
  generator = ref(false),
  preview = shallowRef([]),
  generatorError = ref(''),
  saveDialog = ref(false),
  editName = ref(''),
  editType = ref(0)
const generation = ref({ row: 1, col: 1, rows: 2, cols: 5, prefix: 'A', start: 1, order: 'row' })
let history = createHistory(),
  controller,
  version = 0,
  timer,
  disposed = false
const current = computed(() => parks.value.find((p) => p.id === parkId.value)),
  floors = computed(() => current.value?.park_storey || 1)
const delta = computed(() => changes(base.value, draft.value)),
  changeCount = computed(
    () => delta.value.creates.length + delta.value.updates.length + delta.value.deleteIds.length,
  ),
  dirty = computed(() => editing.value && changeCount.value > 0)
const canUndo = computed(() => {
    historyVersion.value
    return history.canUndo
  }),
  canRedo = computed(() => {
    historyVersion.value
    return history.canRedo
  })
const live = computed(() => new Map(server.value.items.map((c) => [c.id, c]))),
  originals = computed(() => new Map(base.value.map((c) => [c.id, c])))
const spaces = computed(() =>
  editing.value
    ? draft.value.map((c) => {
        const old = originals.value.get(c.id),
          state = live.value.get(c.id)
        return {
          ...c,
          spaces_flag: kind(c) === 0 && state?.spaces_flag === 1 ? 1 : c.spaces_flag,
          editable: state ? state.editable !== false && state.spaces_flag !== 1 : true,
          _change: !old
            ? 'new'
            : old.spaces_name !== c.spaces_name || kind(old) !== kind(c)
              ? 'updated'
              : null,
        }
      })
    : server.value.items,
)
const selectedIds = computed(() => new Set(selected.value.map((s) => s.id))),
  selectedCell = computed(() =>
    selected.value.length === 1 ? spaces.value.find((c) => c.id === selected.value[0].id) : null,
  )
const blocked = computed(() => {
  const ids = new Set([...delta.value.updates.map((c) => c.id), ...delta.value.deleteIds])
  return server.value.items
    .filter((c) => ids.has(c.id) && (c.editable === false || c.spaces_flag === 1))
    .map((c) => c.id)
})
const invalid = computed(() => validateDraft(base.value, draft.value)),
  saveBlocked = computed(
    () =>
      !dirty.value ||
      busy.value ||
      !!conflict.value ||
      uncertain.value ||
      !!invalid.value ||
      blocked.value.length > 0,
  )
const available = computed(() => server.value.items.filter((c) => c.spaces_flag === 0).length),
  capacity = computed(() => server.value.items.filter((c) => c.spaces_flag < 2).length)
const filtered = computed(() =>
  spaces.value.filter(
    (c) =>
      !c._deleted &&
      (!filter.value || c.spaces_name.includes(filter.value)) &&
      (!onlyFree.value || c.spaces_flag === 0),
  ),
)
const visibleRows = computed(() =>
  filtered.value.slice((listPage.value - 1) * 20, listPage.value * 20),
)
const tools = [
  ['select', '↖', '选择 / 框选'],
  ['pan', '✥', '平移'],
  ['0', 'P', '车位'],
  ['2', '═', '道路'],
  ['3', '▥', '墙体'],
  ['4', '↔', '出入口'],
  ['erase', '⌫', '擦除'],
]
const typeNames = { 0: '车位', 1: '不可用', 2: '道路', 3: '墙体', 4: '出入口' }
async function refresh(silent = false) {
  if (!parkId.value) return
  controller?.abort()
  controller = new AbortController()
  const id = ++version,
    park = parkId.value,
    storey = floor.value
  if (!silent) loading.value = true
  refreshing.value = true
  error.value = ''
  try {
    const options = { params: { park_id: park, spaces_storey: storey }, signal: controller.signal }
    let snapshot,
      entry = []
    if (props.admin) snapshot = (await request.get('/spaces/editor', options)).data
    else {
      const [layout, state, currentEntry] = await Promise.all([
        request.get('/spaces/layout', options),
        request.get('/spaces/availability', options),
        request.get('/enter/userEnterOrReservation', { signal: controller.signal }),
      ])
      const flags = new Map(state.data.map((c) => [c.id, c.spaces_flag]))
      snapshot = {
        revision: '',
        items: layout.data.map((c) => ({
          ...c,
          spaces_flag: c.spaces_flag === 0 ? (flags.get(c.id) ?? 1) : c.spaces_flag,
        })),
      }
      entry = currentEntry.data || []
    }
    if (disposed || id !== version) return
    server.value = snapshot
    active.value = entry
    updated.value = new Date()
    if (editing.value && snapshot.revision !== baseRevision.value)
      conflict.value = '地图已被其他操作修改，请放弃草稿并加载最新布局'
    const byId = new Map(spaces.value.map((c) => [c.id, c]))
    const prior = selected.value[0]
    selected.value = selected.value.map((c) => byId.get(c.id)).filter(Boolean)
    if (!props.admin && prior?.spaces_flag === 0 && selected.value[0]?.spaces_flag === 1)
      ElMessage.warning('所选车位已不可用，请重新选择')
  } catch (e) {
    if (!disposed && id === version && e.code !== 'ERR_CANCELED')
      error.value = '地图加载失败，请重试'
  } finally {
    if (id === version) {
      loading.value = false
      refreshing.value = false
    }
  }
}
async function loadParks() {
  controller?.abort()
  controller = new AbortController()
  const id = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await request.get('/parking/catalog', { signal: controller.signal })
    if (disposed || id !== version) return
    parks.value = result.data
    parkId.value = parks.value[0]?.id
    await refresh()
  } catch (e) {
    if (!disposed && e.code !== 'ERR_CANCELED') error.value = '停车场列表加载失败，请重试'
  } finally {
    if (!disposed) loading.value = false
  }
}
async function discard() {
  if (busy.value) return false
  if (dirty.value || uncertain.value) {
    try {
      await ElMessageBox.confirm(
        '当前草稿尚未保存，离开后将丢失。本操作不会撤销已经提交到服务器的修改。',
        '放弃未保存修改？',
        { confirmButtonText: '放弃草稿', cancelButtonText: '继续编辑', type: 'warning' },
      )
    } catch {
      return false
    }
  }
  return true
}
function endEdit() {
  editing.value = false
  selected.value = []
  conflict.value = ''
  conflictIds.value = []
  uncertain.value = false
  tool.value = 'select'
}
async function changePark(value) {
  if (value === parkId.value || !(await discard())) return
  endEdit()
  parkId.value = value
  floor.value = 1
  server.value = { revision: '', items: [] }
  listPage.value = 1
  await refresh()
}
async function changeFloor(value) {
  if (value === floor.value || !(await discard())) return
  endEdit()
  floor.value = value
  server.value = { revision: '', items: [] }
  listPage.value = 1
  await refresh()
}
async function startEdit() {
  if (loading.value || refreshing.value || editing.value) return
  await refresh()
  if (error.value || !current.value) return
  base.value = server.value.items.map((c) => ({ ...c, spaces_flag: kind(c) }))
  draft.value = base.value.map((c) => ({ ...c }))
  history = createHistory(draft.value)
  historyVersion.value++
  baseRevision.value = server.value.revision
  editing.value = true
  selected.value = []
  conflict.value = ''
  uncertain.value = false
  map.value?.fit()
}
async function leaveEdit() {
  if (await discard()) {
    endEdit()
    await refresh()
  }
}
function commit(rows) {
  const message = validateDraft(base.value, rows)
  if (message) {
    ElMessage.warning(message)
    return false
  }
  draft.value = history.commit(rows)
  historyVersion.value++
  const ids = new Set(rows.filter((c) => !c._deleted).map((c) => c.id))
  selected.value = selected.value.filter((c) => ids.has(c.id))
  return true
}
function undo() {
  draft.value = history.undo()
  historyVersion.value++
  selected.value = []
}
function redo() {
  draft.value = history.redo()
  historyVersion.value++
  selected.value = []
}
function locked(ids) {
  return ids.some((id) => {
    const c = live.value.get(id)
    return c && (c.editable === false || c.spaces_flag === 1)
  })
}
function removeRows(ids) {
  if (locked(ids)) {
    ElMessage.warning('预约中、在场或关联异常的位置不能编辑')
    return
  }
  const wanted = new Set(ids)
  commit(
    draft.value.flatMap((c) =>
      wanted.has(c.id) ? (originals.value.has(c.id) ? [{ ...c, _deleted: true }] : []) : [c],
    ),
  )
}
function stroke({ tool: brush, cells }) {
  if (!editing.value || busy.value || conflict.value || uncertain.value) return
  const coords = new Map(draft.value.map((c) => [keyAt(c), c]))
  const targets = cells.map((c) => coords.get(`${c.row}:${c.col}`)).filter(Boolean)
  if (locked(targets.map((c) => c.id))) {
    ElMessage.warning('本次绘制包含不可编辑位置，未修改草稿')
    return
  }
  if (brush === 'erase') {
    removeRows(targets.map((c) => c.id))
    return
  }
  const type = Number(brush),
    patch = new Map()
  for (const point of cells) {
    const old = coords.get(`${point.row}:${point.col}`)
    if (old) {
      if (old._deleted) {
        ElMessage.warning('请先撤销该位置的删除，再修改类型')
        return
      }
      patch.set(old.id, { ...old, spaces_flag: type })
    } else {
      const cell = newCell(point.row, point.col, type)
      patch.set(cell.id, cell)
    }
  }
  const next = new Map(draft.value.map((c) => [c.id, c]))
  patch.forEach((c, id) => next.set(id, c))
  commit([...next.values()])
}
function applyProperties() {
  const cell = selectedCell.value
  if (!cell || locked([cell.id])) return
  if (!editName.value.trim() || editName.value.length > 10) {
    ElMessage.warning('名称需为1至10个字符')
    return
  }
  commit(
    draft.value.map((c) =>
      c.id === cell.id
        ? { ...c, spaces_name: editName.value.trim(), spaces_flag: editType.value }
        : c,
    ),
  )
}
function selectRow(row) {
  selected.value = editing.value
    ? selectedIds.value.has(row.id)
      ? selected.value.filter((c) => c.id !== row.id)
      : [...selected.value, row]
    : selectedIds.value.has(row.id)
      ? []
      : [row]
  map.value?.focus(row)
}
function previewGeneration() {
  try {
    preview.value = generateCells(generation.value, draft.value)
    generatorError.value = ''
  } catch (e) {
    preview.value = []
    generatorError.value = e.message
  }
}
async function addGenerated() {
  if (!preview.value.length) return
  if (commit([...draft.value, ...preview.value])) {
    generator.value = false
    preview.value = []
    await nextTick()
    map.value?.fit()
  }
}
async function save() {
  if (saveBlocked.value) return
  busy.value = true
  // Stop a pending poll before submission; its older snapshot must not overwrite the save response.
  controller?.abort()
  version++
  loading.value = false
  refreshing.value = false
  try {
    const result = await request.post('/spaces/layout/batch', {
      parkId: parkId.value,
      floor: floor.value,
      baseRevision: baseRevision.value,
      ...delta.value,
    })
    server.value = result.data
    updated.value = new Date()
    saveDialog.value = false
    endEdit()
    ElMessage.success('地图已整批保存')
  } catch (e) {
    saveDialog.value = false
    if (e.response?.status === 409) {
      conflict.value = e.response.data.message
      conflictIds.value = e.response.data.data?.conflictIds || []
    } else if (!e.response && e.code !== 1) {
      uncertain.value = true
      ElMessage.warning('保存结果待核对，正在读取服务器状态；不会自动重复提交')
    }
    await refresh(true)
  } finally {
    busy.value = false
  }
}
async function reserve() {
  if (busy.value || active.value.length || selectedCell.value?.spaces_flag !== 0) return
  busy.value = true
  try {
    await request.patch('/enter/reservation', { id: selectedCell.value.id })
    ElMessage.success('预约成功')
    selected.value = []
  } catch {
  } finally {
    await refresh(true)
    busy.value = false
  }
}
async function cancel(row) {
  if (busy.value) return
  try {
    await ElMessageBox.confirm('确认取消此预约？', '取消预约', {
      confirmButtonText: '确认取消',
      cancelButtonText: '保留预约',
      type: 'warning',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    await request.post('/enter/cancelReservation', null, { params: { id: row.id } })
    ElMessage.success('预约已取消')
  } catch {
  } finally {
    await refresh(true)
    busy.value = false
  }
}
function visibility() {
  clearInterval(timer)
  if (!document.hidden) {
    if (!busy.value) refresh(true)
    timer = setInterval(() => {
      if (!busy.value && !refreshing.value) refresh(true)
    }, 15000)
  }
}
function beforeUnload(e) {
  if (dirty.value || busy.value || uncertain.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}
onBeforeRouteLeave(() => discard())
onMounted(async () => {
  await loadParks()
  if (disposed) return
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('beforeunload', beforeUnload)
  timer = setInterval(() => {
    if (!document.hidden && !busy.value && !refreshing.value) refresh(true)
  }, 15000)
})
onBeforeUnmount(() => {
  disposed = true
  version++
  controller?.abort()
  clearInterval(timer)
  document.removeEventListener('visibilitychange', visibility)
  window.removeEventListener('beforeunload', beforeUnload)
})
watch([filter, onlyFree], () => (listPage.value = 1))
watch(
  () => filtered.value.length,
  (n) => (listPage.value = Math.min(listPage.value, Math.max(1, Math.ceil(n / 20)))),
)
watch(selectedCell, (c) => {
  editName.value = c?.spaces_name || ''
  editType.value = c ? kind(c) : 0
})
watch(
  generation,
  () => {
    preview.value = []
    generatorError.value = ''
  },
  { deep: true },
)
</script>
<template>
  <PageShell
    :title="admin ? '车位管理' : '车位预约'"
    :description="
      admin ? '把空间安排得井然有序。先预览，再一次保存。' : '看清空闲位置，让下一次停车更从容。'
    "
  >
    <template #actions
      ><el-button :loading="refreshing" :disabled="busy" @click="current ? refresh() : loadParks()"
        >刷新地图</el-button
      ><el-button
        v-if="admin && !editing"
        type="primary"
        :disabled="!current || loading"
        @click="startEdit"
        >编辑地图</el-button
      ><el-button v-if="editing" :disabled="busy" @click="leaveEdit">退出编辑</el-button></template
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false"
      ><el-button link @click="current ? refresh() : loadParks()">重试</el-button></el-alert
    >
    <div class="map-context">
      <div class="context-pickers">
        <span class="context-icon">P</span>
        <div>
          <span class="eyebrow">{{ admin ? 'PARKING WORKSPACE' : 'FIND YOUR SPACE' }}</span>
          <div class="picker-row">
            <el-select
              :model-value="parkId"
              aria-label="停车场"
              placeholder="选择停车场"
              :disabled="busy"
              @update:model-value="changePark"
              ><el-option
                v-for="p in parks"
                :key="p.id"
                :label="p.park_name"
                :value="p.id" /></el-select
            ><el-select
              :model-value="floor"
              aria-label="楼层"
              :disabled="busy"
              @update:model-value="changeFloor"
              ><el-option v-for="f in floors" :key="f" :label="`${f} 楼`" :value="f"
            /></el-select>
          </div>
        </div>
      </div>
      <div class="map-metrics">
        <div>
          <strong class="free-count">{{ available }}</strong
          ><span>当前空闲</span>
        </div>
        <div>
          <strong>{{ capacity - available }}</strong
          ><span>不可用</span>
        </div>
        <div>
          <strong>{{ capacity }}</strong
          ><span>车位总数</span>
        </div>
      </div>
    </div>
    <div v-if="!admin && active.length" class="active-booking">
      <div v-for="row in active" :key="row.id">
        <StatusTag :value="row.flag" :reservation="row.reservation" /><strong>{{
          row.park_name
        }}</strong
        ><span>{{
          row.flag === 0
            ? '预约到期：' + dateTime(row.expires_at)
            : '入场时间：' + dateTime(row.enter_time)
        }}</span
        ><el-button
          v-if="row.flag === 0 && row.reservation === 1"
          type="danger"
          plain
          :loading="busy"
          @click="cancel(row)"
          >取消预约</el-button
        >
      </div>
    </div>
    <el-alert
      v-if="conflict || uncertain || blocked.length"
      :title="
        conflict ||
        (uncertain
          ? '保存结果待核对：当前已读取服务器状态，请放弃草稿后查看正式布局，再决定是否继续编辑'
          : `有 ${blocked.length} 个待修改位置已不可用，撤销相关修改后再保存`)
      "
      type="warning"
      :closable="false"
      class="conflict-alert"
      ><span v-if="conflictIds.length">位置 ID：{{ conflictIds.join('、') }}</span
      ><el-button v-if="conflict || uncertain" link @click="leaveEdit"
        >放弃草稿并加载最新布局</el-button
      ></el-alert
    >
    <div v-if="editing" class="editor-bar">
      <div class="editor-tools">
        <button
          v-for="[value, icon, label] in tools"
          :key="value"
          type="button"
          :class="{ active: tool === value }"
          :aria-pressed="tool === value"
          :disabled="busy || !!conflict || uncertain"
          @click="tool = value"
        >
          <b>{{ icon }}</b
          ><span>{{ label }}</span>
        </button>
      </div>
      <div class="history-tools">
        <el-button :disabled="!canUndo || busy" @click="undo">撤销</el-button
        ><el-button :disabled="!canRedo || busy" @click="redo">重做</el-button
        ><el-button :disabled="busy || !!conflict || uncertain" @click="generator = true"
          >批量生成车位</el-button
        >
      </div>
    </div>
    <div class="map-workspace" :class="{ 'is-editor': editing }">
      <div class="map-main">
        <div v-loading="loading">
          <ParkingMap
            ref="map"
            v-model="selected"
            :spaces="spaces"
            :multiple="editing"
            :editing="editing"
            :tool="tool"
            :context-key="`${parkId}:${floor}`"
            :disabled="busy || !!conflict || uncertain || (!admin && !!active.length)"
            @stroke="stroke"
          />
        </div>
        <p class="update-note">
          {{
            updated
              ? `状态更新于 ${updated.toLocaleTimeString('zh-CN')} · 页面可见时每 15 秒刷新`
              : '正在获取楼层信息'
          }}
          <span v-if="editing"> · 虚线：绿 / 新增　黄 / 修改　红 / 删除</span>
        </p>
      </div>
      <aside class="space-inspector">
        <span class="eyebrow">{{ editing ? 'SELECTION & PROPERTIES' : 'SPACE DETAILS' }}</span>
        <div class="selection-actions">
          <h2>
            {{
              selectedCell
                ? selectedCell.spaces_name
                : selected.length
                  ? '已选择多个位置'
                  : '选择一个位置'
            }}
          </h2>
          <p>
            已选择 <strong>{{ selected.length }}</strong> 个位置
            <el-button v-if="selected.length" link :disabled="busy" @click="selected = []"
              >清空选择</el-button
            >
          </p>
        </div>
        <template v-if="selectedCell"
          ><div class="space-status" :class="{ free: selectedCell.spaces_flag === 0 }">
            {{ typeNames[selectedCell.spaces_flag]
            }}<span v-if="selectedCell._change"> · 草稿</span>
          </div>
          <dl>
            <dt>所在楼层</dt>
            <dd>{{ floor }} 楼</dd>
            <dt>位置坐标</dt>
            <dd>{{ selectedCell.spaces_row }} 行 · {{ selectedCell.spaces_col }} 列</dd>
          </dl></template
        >
        <p v-else class="inspector-help">
          {{
            editing
              ? '点击位置查看属性；拖动框选可一次选中多个位置。'
              : '点击地图上的绿色车位，或在下方列表中搜索并定位。'
          }}
        </p>
        <template v-if="editing"
          ><el-form v-if="selectedCell" label-position="top"
            ><el-form-item label="位置名称"
              ><el-input
                v-model="editName"
                maxlength="10"
                :disabled="busy || !selectedCell.editable" /></el-form-item
            ><el-form-item label="位置类型"
              ><el-select v-model="editType" :disabled="busy || !selectedCell.editable"
                ><el-option
                  v-for="t in [0, 2, 3, 4]"
                  :key="t"
                  :label="typeNames[t]"
                  :value="t" /></el-select></el-form-item
            ><el-button
              class="wide-button"
              :disabled="busy || !selectedCell.editable || !!conflict || uncertain"
              @click="applyProperties"
              >应用到草稿</el-button
            ></el-form
          ><el-button
            class="wide-button remove-button"
            type="danger"
            plain
            :disabled="!selected.length || busy || !!conflict || uncertain"
            @click="removeRows(selected.map((c) => c.id))"
            >删除所选</el-button
          >
          <p class="inspector-help">
            预约、在场或关联异常的位置不可修改。已存在的位置保持原坐标和 ID。
          </p></template
        >
        <template v-else-if="!admin"
          ><el-button
            class="wide-button reserve-button"
            type="primary"
            :loading="busy"
            :disabled="selectedCell?.spaces_flag !== 0 || !!active.length || loading || !!error"
            @click="reserve"
            >确认预约</el-button
          >
          <p class="inspector-help">
            预约保留时间以成功后的到期时间为准。选择位置不会提前锁定车位。
          </p></template
        >
        <template v-else
          ><el-button
            class="wide-button"
            type="primary"
            :disabled="!current || loading"
            @click="startEdit"
            >进入编辑模式</el-button
          >
          <p class="inspector-help">
            当前为浏览模式。编辑时可绘制位置、批量生成编号并预览保存。
          </p></template
        >
      </aside>
    </div>
    <div v-if="editing" class="save-bar">
      <div>
        <strong>{{ changeCount }} 项待保存</strong
        ><span
          >新增 {{ delta.creates.length }} · 修改 {{ delta.updates.length }} · 删除
          {{ delta.deleteIds.length }}</span
        ><small v-if="invalid">{{ invalid }}</small>
      </div>
      <el-button type="primary" :disabled="saveBlocked" :loading="busy" @click="saveDialog = true"
        >预览并保存</el-button
      >
    </div>
    <section class="surface space-list">
      <div class="list-heading">
        <div>
          <h2>位置列表</h2>
          <p>搜索、定位，也支持键盘选择</p>
        </div>
        <div class="list-filters">
          <el-input v-model="filter" placeholder="搜索位置名称" clearable /><el-checkbox
            v-model="onlyFree"
            >仅看空闲车位</el-checkbox
          >
        </div>
      </div>
      <div class="table-scroll">
        <el-table :data="visibleRows" row-key="id" empty-text="暂无符合条件的位置"
          ><el-table-column prop="spaces_name" label="名称" min-width="130" /><el-table-column
            label="坐标"
            min-width="120"
            ><template #default="{ row }"
              >{{ row.spaces_row }} 行 {{ row.spaces_col }} 列</template
            ></el-table-column
          ><el-table-column label="状态" min-width="120"
            ><template #default="{ row }"
              ><el-tag :type="row.spaces_flag === 0 ? 'success' : 'info'">{{
                typeNames[row.spaces_flag]
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="操作" min-width="130"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="busy || (!admin && !!active.length)"
                @click="selectRow(row)"
                >{{ selectedIds.has(row.id) ? '取消选择' : '选择' }}</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </div>
      <el-pagination
        class="map-pagination"
        v-model:current-page="listPage"
        :page-size="20"
        :total="filtered.length"
        layout="prev,pager,next"
      />
    </section>
    <el-dialog v-model="generator" title="批量生成车位" width="640px" :close-on-click-modal="false"
      ><p class="dialog-intro">从指定坐标生成矩形车位区域，不覆盖已有位置。生成结果先进入草稿。</p>
      <el-form label-position="top"
        ><div class="generation-grid">
          <el-form-item label="起始行"
            ><el-input-number v-model="generation.row" :min="1" :max="100" /></el-form-item
          ><el-form-item label="起始列"
            ><el-input-number v-model="generation.col" :min="1" :max="100" /></el-form-item
          ><el-form-item label="行数"
            ><el-input-number v-model="generation.rows" :min="1" :max="100" /></el-form-item
          ><el-form-item label="列数"
            ><el-input-number v-model="generation.cols" :min="1" :max="100" /></el-form-item
          ><el-form-item label="编号前缀"
            ><el-input v-model="generation.prefix" maxlength="8" /></el-form-item
          ><el-form-item label="起始编号"
            ><el-input-number v-model="generation.start" :min="0" :max="999999"
          /></el-form-item>
        </div>
        <el-form-item label="编号顺序"
          ><el-radio-group v-model="generation.order"
            ><el-radio-button value="row">行优先</el-radio-button
            ><el-radio-button value="column">列优先</el-radio-button></el-radio-group
          ></el-form-item
        ></el-form
      ><el-alert v-if="generatorError" :title="generatorError" type="error" :closable="false" />
      <div v-if="preview.length" class="generation-preview">
        <strong>预览 {{ preview.length }} 个车位</strong
        ><span>{{ preview[0].spaces_name }} → {{ preview.at(-1).spaces_name }}</span>
        <p>
          {{ generation.row }} 行 {{ generation.col }} 列起，{{ generation.rows }} 行 ×
          {{ generation.cols }} 列
        </p>
        <div class="preview-bays">
          <span v-for="p in preview.slice(0, 20)" :key="p.id">{{ p.spaces_name }}</span
          ><span v-if="preview.length > 20">…</span>
        </div>
      </div>
      <template #footer
        ><el-button @click="generator = false">取消</el-button
        ><el-button @click="previewGeneration">生成预览</el-button
        ><el-button type="primary" :disabled="!preview.length" @click="addGenerated"
          >加入草稿</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="saveDialog"
      title="确认整批保存"
      width="520px"
      :close-on-click-modal="!busy"
      :show-close="!busy"
      :close-on-press-escape="!busy"
      ><p>
        将更新 {{ current?.park_name }} · {{ floor }} 楼，共
        {{ changeCount }} 个位置。所有修改会一起成功或一起回滚。
      </p>
      <div class="save-summary">
        <span
          >新增 <b>{{ delta.creates.length }}</b></span
        ><span
          >修改 <b>{{ delta.updates.length }}</b></span
        ><span
          >删除 <b>{{ delta.deleteIds.length }}</b></span
        >
      </div>
      <el-alert
        v-if="saveBlocked"
        :title="
          conflict ||
          invalid ||
          (blocked.length ? '部分位置已不可用，不能保存' : '当前无法保存，请核对状态')
        "
        type="warning"
        :closable="false"
      /><template #footer
        ><el-button :disabled="busy" @click="saveDialog = false">继续编辑</el-button
        ><el-button type="primary" :loading="busy" :disabled="saveBlocked" @click="save"
          >确认保存</el-button
        ></template
      ></el-dialog
    >
  </PageShell>
</template>
<style scoped>
.map-context {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: center;
  padding: 22px 24px;
  background: #fff;
  border: 1px solid #e4ebf4;
  border-radius: 16px;
  margin-bottom: 20px;
}
.context-pickers {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.context-icon {
  background: #edf4ff;
  color: #2563eb;
  font-size: 28px;
  font-weight: 800;
  width: 54px;
  height: 54px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  flex-shrink: 0;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 1.8px;
  color: #8797ad;
  font-weight: 700;
}
.picker-row {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
.picker-row .el-select:first-child {
  width: 240px;
}
.picker-row .el-select:last-child {
  width: 100px;
}
.map-metrics {
  display: flex;
  gap: 32px;
  flex-shrink: 0;
}
.map-metrics > div {
  display: grid;
  gap: 5px;
}
.map-metrics strong {
  font-size: 25px;
  font-variant-numeric: tabular-nums;
  color: #3b4e68;
}
.map-metrics .free-count {
  color: #059669;
}
.map-metrics span {
  font-size: 11px;
  color: #8b9ab0;
}
.map-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 270px;
  gap: 20px;
  align-items: start;
}
.map-main {
  min-width: 0;
}
.space-inspector {
  background: #fff;
  border: 1px solid #e4ebf4;
  border-radius: 18px;
  padding: 24px;
  min-width: 0;
}
.space-inspector h2 {
  font-size: 25px;
  margin: 18px 0 8px;
  overflow-wrap: anywhere;
}
.selection-actions p {
  color: #8797ad;
  font-size: 12px;
}
.space-status {
  display: inline-block;
  background: #f0f3f7;
  color: #64748b;
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 7px;
  margin: 10px 0;
}
.space-status.free {
  background: #e8f8ef;
  color: #138b64;
}
dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  font-size: 12px;
  margin: 18px 0 26px;
}
dt {
  color: #8392a7;
}
dd {
  text-align: right;
  margin: 0;
  color: #34465d;
}
.wide-button {
  width: 100%;
  margin: 8px 0 0 !important;
}
.reserve-button {
  height: 44px;
}
.inspector-help {
  font-size: 12px;
  line-height: 1.9;
  color: #94a3b8;
  margin-top: 18px;
}
.update-note {
  color: #8b9bb0;
  font-size: 11px;
  line-height: 1.8;
  margin: 12px 4px 20px;
}
.active-booking {
  padding: 16px 22px;
  border: 1px solid #bfd5fc;
  border-radius: 14px;
  background: #eff6ff;
  margin-bottom: 20px;
}
.active-booking > div {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  align-items: center;
  font-size: 13px;
}
.editor-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fff;
  border: 1px solid #e4ebf4;
  border-radius: 14px;
  padding: 10px 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.editor-tools {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.editor-tools button {
  display: grid;
  justify-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: #64748b;
  border-radius: 9px;
  padding: 9px 12px;
  cursor: pointer;
  font: inherit;
}
.editor-tools button.active {
  background: #eaf2ff;
  color: #2563eb;
}
.editor-tools button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.editor-tools button b {
  font-size: 17px;
  line-height: 20px;
}
.editor-tools button span {
  font-size: 11px;
}
.history-tools {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.history-tools .el-button {
  margin: 0;
}
.save-bar {
  position: sticky;
  bottom: 16px;
  z-index: 20;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  background: #14233b;
  color: #fff;
  border-radius: 14px;
  padding: 18px 24px;
  margin: 0 0 24px;
  box-shadow: 0 8px 24px #14233b24;
}
.save-bar > div {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.save-bar span {
  font-size: 12px;
  color: #adc0dc;
}
.save-bar small {
  color: #fbbf24;
}
.conflict-alert {
  margin-bottom: 16px;
}
.space-list h2 {
  margin: 0;
  font-size: 17px;
}
.space-list p {
  font-size: 12px;
  color: #94a3b8;
}
.list-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.list-filters {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.list-filters .el-input {
  width: 220px;
}
.map-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}
.generation-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.generation-grid .el-input-number {
  width: 100%;
}
.dialog-intro {
  font-size: 13px;
  line-height: 1.8;
  color: #8494aa;
}
.generation-preview {
  padding: 16px;
  background: #f0f8f5;
  border-radius: 12px;
  color: #167b60;
}
.generation-preview > span {
  margin-left: 16px;
  font-size: 12px;
}
.generation-preview p {
  font-size: 12px;
}
.preview-bays {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.preview-bays span {
  padding: 6px;
  border: 1px solid #87cbb4;
  font-size: 11px;
  border-radius: 4px;
}
.save-summary {
  display: flex;
  gap: 20px;
  padding: 24px 0;
  color: #64748b;
}
.save-summary b {
  font-size: 22px;
  color: #2563eb;
  margin-left: 8px;
}
@media (max-width: 1100px) {
  .map-context {
    flex-wrap: wrap;
  }
  .map-workspace {
    grid-template-columns: minmax(0, 1fr) 240px;
  }
  .space-inspector {
    padding: 18px;
  }
}
@media (max-width: 767px) {
  .map-context {
    padding: 16px;
    gap: 18px;
  }
  .context-pickers {
    width: 100%;
    gap: 10px;
  }
  .context-pickers > div {
    flex: 1;
    min-width: 0;
  }
  .context-icon {
    width: 40px;
    height: 46px;
  }
  .picker-row .el-select:first-child {
    width: 100%;
    min-width: 0;
  }
  .picker-row .el-select:last-child {
    width: 86px;
    flex-shrink: 0;
  }
  .map-metrics {
    width: 100%;
    justify-content: space-between;
    padding: 8px 4px 0;
    border-top: 1px solid #edf1f7;
  }
  .map-metrics strong {
    font-size: 22px;
  }
  .map-workspace {
    grid-template-columns: 1fr;
  }
  .space-inspector {
    padding: 20px;
  }
  .space-inspector h2 {
    font-size: 22px;
  }
  .map-main .update-note {
    margin-bottom: 0;
  }
  .space-inspector dl {
    margin: 12px 0;
  }
  .save-bar {
    bottom: 10px;
    padding: 14px;
    align-items: center;
    margin-top: 16px;
  }
  .save-bar > div {
    gap: 6px;
  }
  .save-bar strong {
    font-size: 14px;
  }
  .save-bar span {
    font-size: 11px;
  }
  .editor-tools button {
    padding: 8px 10px;
  }
  .list-filters {
    width: 100%;
  }
  .list-filters .el-input {
    width: 100%;
  }
  .generation-grid {
    gap: 0 12px;
  }
  .active-booking > div {
    gap: 10px;
  }
  .space-list {
    margin-top: 20px;
  }
}
</style>
