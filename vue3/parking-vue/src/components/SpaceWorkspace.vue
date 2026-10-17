<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import PageShell from './PageShell.vue'
import ParkingMap from './ParkingMap.vue'
import StatusTag from './StatusTag.vue'
import { dateTime } from '@/utils/format'
const props = defineProps({ admin: Boolean }),
  parks = ref([]),
  parkId = ref(),
  floor = ref(1),
  spaces = ref([]),
  selected = ref([]),
  active = ref([]),
  loading = ref(false),
  error = ref(''),
  busy = ref(false),
  filter = ref(''),
  listPage = ref(1),
  dialog = ref(false),
  editing = ref(false),
  model = ref({}),
  form = ref()
const current = computed(() => parks.value.find((p) => p.id === parkId.value)),
  floors = computed(() => current.value?.park_storey || 1),
  filtered = computed(() =>
    spaces.value.filter((s) => !filter.value || s.spaces_name.includes(filter.value)),
  ),
  visibleRows = computed(() => filtered.value.slice((listPage.value - 1) * 20, listPage.value * 20))
let controller,
  version = 0,
  timer,
  disposed = false
async function refresh() {
  if (!parkId.value) return
  controller?.abort()
  controller = new AbortController()
  const id = ++version
  loading.value = true
  error.value = ''
  try {
    const params = { park_id: parkId.value, spaces_storey: floor.value },
      options = { params, signal: controller.signal }
    const [layout, state, entry] = await Promise.all([
      request.get('/spaces/layout', options),
      request.get('/spaces/availability', options),
      props.admin
        ? Promise.resolve({ data: [] })
        : request.get('/enter/userEnterOrReservation', { signal: controller.signal }),
    ])
    if (disposed || id !== version) return
    const flags = new Map(state.data.map((s) => [s.id, s.spaces_flag]))
    spaces.value = layout.data.map((s) => ({
      ...s,
      spaces_flag: flags.get(s.id) ?? s.spaces_flag,
      park_name: current.value?.park_name,
    }))
    selected.value = selected.value
      .map((s) => spaces.value.find((v) => v.id === s.id))
      .filter(Boolean)
    active.value = entry.data || []
  } catch (e) {
    if (e.code !== 'ERR_CANCELED' && !disposed && id === version)
      error.value = '车位加载失败，请重试'
  } finally {
    if (id === version) loading.value = false
  }
}
function changePark() {
  floor.value = 1
  spaces.value = []
  selected.value = []
  listPage.value = 1
  refresh()
}
function changeFloor() {
  spaces.value = []
  selected.value = []
  listPage.value = 1
  refresh()
}
function selectRow(row) {
  if (props.admin) {
    selected.value = selected.value.some((s) => s.id === row.id)
      ? selected.value.filter((s) => s.id !== row.id)
      : [...selected.value, row]
  } else if (!active.value.length && row.spaces_flag === 0) {
    selected.value = [row]
  }
}
async function reserve() {
  if (busy.value || selected.value.length !== 1) return
  busy.value = true
  try {
    await request.patch('/enter/reservation', { id: selected.value[0].id })
    ElMessage.success('预约成功')
    selected.value = []
    await refresh()
  } catch {
  } finally {
    busy.value = false
  }
}
async function cancel(row) {
  try {
    await ElMessageBox.confirm('确认取消此预约？', '取消预约', {
      confirmButtonText: '确认取消',
      cancelButtonText: '保留预约',
      type: 'warning',
    })
  } catch {
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    await request.post('/enter/cancelReservation', null, { params: { id: row.id } })
    ElMessage.success('预约已取消')
    await refresh()
  } catch {
  } finally {
    busy.value = false
  }
}
function open(row) {
  if (row?.spaces_flag === 1) {
    ElMessage.warning('占用中的车位不能修改')
    return
  }
  editing.value = !!row
  model.value = row
    ? { ...row }
    : {
        park_name: current.value.park_name,
        spaces_name: '',
        spaces_row: 1,
        spaces_col: 1,
        spaces_storey: floor.value,
        spaces_flag: 0,
      }
  dialog.value = true
}
async function save() {
  if (busy.value || !(await form.value.validate().catch(() => false))) return
  busy.value = true
  try {
    await request({
      url: editing.value ? '/spaces/update' : '/spaces/addSpace',
      method: editing.value ? 'put' : 'post',
      data: model.value,
    })
    dialog.value = false
    selected.value = []
    await refresh()
    ElMessage.success('保存成功')
  } catch {
  } finally {
    busy.value = false
  }
}
async function remove() {
  if (!selected.value.length || busy.value) return
  if (selected.value.some((s) => s.spaces_flag === 1)) {
    ElMessage.warning('请先移除占用中的车位')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selected.value.length} 个位置？`, '批量删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    await request.delete('/spaces/deleteBatch', { data: selected.value })
    selected.value = []
    await refresh()
    ElMessage.success('删除成功')
  } catch {
  } finally {
    busy.value = false
  }
}
function visibility() {
  clearInterval(timer)
  if (!document.hidden) {
    refresh()
    timer = setInterval(() => {
      if (!busy.value && !loading.value) refresh()
    }, 15000)
  }
}
onMounted(async () => {
  try {
    controller = new AbortController()
    const result = await request.get('/parking/catalog', { signal: controller.signal })
    if (disposed) return
    parks.value = result.data
    parkId.value = parks.value[0]?.id
    await refresh()
  } catch {
    if (disposed) return
    error.value = '停车场列表加载失败'
  }
  if (disposed) return
  document.addEventListener('visibilitychange', visibility)
  timer = setInterval(() => {
    if (!document.hidden && !busy.value && !loading.value) refresh()
  }, 15000)
})
onBeforeUnmount(() => {
  disposed = true
  version++
  controller?.abort()
  clearInterval(timer)
  document.removeEventListener('visibilitychange', visibility)
})
watch(filter, () => (listPage.value = 1))
watch(
  () => filtered.value.length,
  (length) => (listPage.value = Math.min(listPage.value, Math.max(1, Math.ceil(length / 20)))),
)
</script>
<template>
  <PageShell
    :title="admin ? '车位管理' : '车位预约'"
    :description="
      admin ? '维护楼层地图，已预约或在场车位不能编辑或删除' : '选择停车场和楼层，预约一个空闲车位'
    "
    ><template #actions
      ><el-button :loading="loading" @click="refresh">刷新车位</el-button
      ><el-button v-if="admin" type="primary" :disabled="!current" @click="open()"
        >添加位置</el-button
      ></template
    ><el-alert v-if="error" :title="error" type="error" :closable="false"
      ><el-button link @click="refresh">重试</el-button></el-alert
    >
    <div class="space-selectors surface">
      <el-select v-model="parkId" placeholder="选择停车场" aria-label="停车场" @change="changePark"
        ><el-option v-for="p in parks" :key="p.id" :label="p.park_name" :value="p.id" /></el-select
      ><el-select v-model="floor" placeholder="选择楼层" aria-label="楼层" @change="changeFloor"
        ><el-option v-for="f in floors" :key="f" :label="f + ' 楼'" :value="f" /></el-select
      ><span class="muted"
        >空闲 {{ spaces.filter((s) => s.spaces_flag === 0).length }} /
        {{ spaces.filter((s) => s.spaces_flag < 2).length }} 个车位 · 15 秒自动刷新</span
      >
    </div>
    <div v-if="!admin && active.length" class="reservation-summary surface">
      <div v-for="row in active" :key="row.id">
        <StatusTag :value="row.flag" :reservation="row.reservation" /><strong>{{
          row.park_name
        }}</strong
        ><span class="muted">{{
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
    <div v-loading="loading">
      <ParkingMap
        :spaces="spaces"
        v-model="selected"
        :multiple="admin"
        :disabled="busy || (!admin && !!active.length)"
      />
    </div>
    <div class="selection-actions surface">
      <span
        >已选择 <strong>{{ selected.length }}</strong> 个位置
        <span v-if="selected.length === 1" class="muted"
          >· {{ selected[0].spaces_name }}</span
        ></span
      >
      <div v-if="admin">
        <el-button :disabled="selected.length !== 1 || busy" @click="open(selected[0])"
          >编辑位置</el-button
        ><el-button type="danger" plain :disabled="!selected.length || busy" @click="remove"
          >删除所选</el-button
        >
      </div>
      <el-button
        v-else
        type="primary"
        :loading="busy"
        :disabled="selected.length !== 1 || !!active.length"
        @click="reserve"
        >确认预约</el-button
      >
    </div>
    <section class="surface space-list">
      <div class="page-heading">
        <div>
          <h2>位置列表</h2>
          <p>可通过列表选择位置，支持键盘操作。</p>
        </div>
        <el-input v-model="filter" placeholder="搜索位置名称" clearable style="max-width: 220px" />
      </div>
      <div class="table-scroll">
        <el-table :data="visibleRows" row-key="id" empty-text="暂无位置"
          ><el-table-column prop="spaces_name" label="名称" min-width="130" /><el-table-column
            label="坐标"
            min-width="120"
            ><template #default="{ row }"
              >{{ row.spaces_row }} 行 {{ row.spaces_col }} 列</template
            ></el-table-column
          ><el-table-column label="状态" min-width="120"
            ><template #default="{ row }"
              ><StatusTag :value="row.spaces_flag" kind="space" /></template></el-table-column
          ><el-table-column label="操作" min-width="100"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="busy || (!admin && (row.spaces_flag !== 0 || !!active.length))"
                @click="selectRow(row)"
                >{{ selected.some((s) => s.id === row.id) ? '取消选择' : '选择' }}</el-button
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
    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑位置' : '添加位置'"
      width="520px"
      destroy-on-close
      ><el-form ref="form" :model="model" label-position="top"
        ><div class="form-grid">
          <el-form-item
            label="名称"
            prop="spaces_name"
            :rules="[{ required: true, message: '请输入位置名称' }]"
            ><el-input v-model="model.spaces_name" maxlength="10" /></el-form-item
          ><el-form-item label="类型"
            ><el-select v-model="model.spaces_flag"
              ><el-option
                v-for="(label, key) in { 0: '车位', 2: '道路', 3: '墙体', 4: '出入口' }"
                :key="key"
                :label="label"
                :value="Number(key)" /></el-select></el-form-item
          ><el-form-item label="行"
            ><el-input-number
              v-model="model.spaces_row"
              :min="1"
              :max="100"
              :disabled="editing" /></el-form-item
          ><el-form-item label="列"
            ><el-input-number v-model="model.spaces_col" :min="1" :max="100" :disabled="editing"
          /></el-form-item></div></el-form
      ><template #footer
        ><el-button @click="dialog = false" :disabled="busy">取消</el-button
        ><el-button type="primary" @click="save" :loading="busy">保存</el-button></template
      ></el-dialog
    ></PageShell
  >
</template>
<style scoped>
.space-selectors {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
  padding: 18px;
}
.space-selectors .el-select {
  width: 200px;
}
.space-selectors .muted {
  font-size: 12px;
}
.reservation-summary {
  margin-bottom: 20px;
}
.reservation-summary > div {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
}
.selection-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 16px 0 22px;
  padding: 16px 20px;
  gap: 10px;
  flex-wrap: wrap;
}
.space-list h2 {
  font-size: 17px;
  margin: 0 0 8px;
}
.space-list .page-heading {
  margin-bottom: 16px;
}
.map-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
@media (max-width: 600px) {
  .space-selectors .el-select {
    width: 100%;
  }
}
</style>
