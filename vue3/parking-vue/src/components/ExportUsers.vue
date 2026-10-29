<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { jwtDecode } from 'jwt-decode'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import { useTokenStore } from '@/stores/token'
import request from '@/utils/request'
import { cleanQuery } from '@/utils/format'
const props = defineProps({ filters: { type: Object, required: true } })
// 按用户隔离当前标签页的任务记录，刷新或离开页面后可恢复自己的导出状态。
const storageKey = `parking-export:${jwtDecode(useTokenStore().token).claims.id}`
const job = ref(sessionStorage.getItem(storageKey) || ''),
  state = ref(''),
  busy = ref(false),
  failed = ref(false),
  uncertain = ref(false)
let timer,
  controller,
  actionController,
  disposed = false,
  requestKey = null,
  requestFilters = null
try {
  const saved = JSON.parse(sessionStorage.getItem(storageKey + ':pending') || 'null')
  if (
    saved &&
    /^[A-Za-z0-9-]{16,64}$/.test(saved.key) &&
    saved.filters &&
    typeof saved.filters === 'object'
  ) {
    requestKey = saved.key
    requestFilters = saved.filters
    uncertain.value = true
  }
} catch {
  sessionStorage.removeItem(storageKey + ':pending')
}
function clearPending() {
  requestKey = null
  requestFilters = null
  uncertain.value = false
  sessionStorage.removeItem(storageKey + ':pending')
}
const pending = () =>
  job.value && !['DONE', 'EXPIRED', 'DEAD', 'DEAD_PENDING', 'PAUSED'].includes(state.value)
function schedule() {
  clearTimeout(timer)
  // 只在页面可见且任务未结束时轮询；使用单次定时器避免慢请求形成重叠轮询。
  if (!disposed && !document.hidden && pending() && !failed.value)
    timer = setTimeout(() => refresh(true), 2000)
}
async function refresh(silent = true) {
  if (disposed || document.hidden || !job.value || state.value === 'PAUSED') return
  controller?.abort()
  const own = new AbortController()
  controller = own
  const currentJob = job.value
  try {
    const result = await request.get(`/admin/background/exports/${currentJob}`, {
      signal: own.signal,
      feedback: !silent,
    })
    if (disposed || controller !== own || currentJob !== job.value) return
    state.value = result.data.status
    failed.value = false
  } catch (error) {
    if (!disposed && controller === own && error.code !== 'ERR_CANCELED') {
      failed.value = true
      if (error.message === '导出任务不存在') {
        job.value = ''
        state.value = 'EXPIRED'
        sessionStorage.removeItem(storageKey)
      }
    }
  } finally {
    if (controller === own) schedule()
  }
}
async function saveBlob(response) {
  if (!response.data.type.includes('text/csv')) {
    ElMessage.error('导出文件暂不可用，请刷新任务状态')
    return
  }
  const url = URL.createObjectURL(response.data),
    link = document.createElement('a')
  link.href = url
  link.download = 'users.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  ElMessage.success('已开始下载')
}
async function create() {
  if (busy.value) return
  busy.value = true
  actionController?.abort()
  actionController = new AbortController()
  try {
    const capability = await request.get('/admin/background/capabilities', {
      signal: actionController.signal,
    })
    if (disposed) return
    // RabbitMQ 后台功能关闭时走兼容同步导出，普通导出能力不依赖可选中间件。
    if (!capability.data.exports) {
      clearPending()
      if (pending()) {
        clearTimeout(timer)
        controller?.abort()
        controller = null
        state.value = 'PAUSED'
      }
      const response = await request.get('/user/export', {
        params: cleanQuery(props.filters),
        responseType: 'blob',
        signal: actionController.signal,
      })
      if (!disposed) await saveBlob(response)
      return
    }
    if (pending()) {
      await refresh(false)
      return
    }
    // 请求结果不确定时复用同一幂等键与筛选快照，防止重试创建第二个任务或改变第一次请求含义。
    requestKey ||= crypto.randomUUID()
    requestFilters ||= cleanQuery({ ...props.filters })
    sessionStorage.setItem(
      storageKey + ':pending',
      JSON.stringify({ key: requestKey, filters: requestFilters }),
    )
    const response = await request.post('/admin/background/exports', requestFilters, {
      headers: { 'Idempotency-Key': requestKey },
      signal: actionController.signal,
    })
    if (disposed) return
    job.value = response.data.id
    state.value = 'READY'
    failed.value = false
    sessionStorage.setItem(storageKey, job.value)
    clearPending()
    schedule()
    ElMessage.success('导出任务已提交')
  } catch (error) {
    localFailure(error, '导出未完成，请重试')
    // 明确业务拒绝才清理待确认请求；断网或超时不能证明服务端未创建任务，保留原键供重试。
    if (error.code === 1 || [400, 401, 403].includes(error.response?.status)) clearPending()
    else if (requestKey && !disposed) uncertain.value = true
  } finally {
    if (!disposed) busy.value = false
  }
}
async function download() {
  if (busy.value) return
  busy.value = true
  actionController?.abort()
  actionController = new AbortController()
  try {
    const result = await request.get(`/admin/background/exports/${job.value}/file`, {
      responseType: 'blob',
      signal: actionController.signal,
    })
    if (!disposed) await saveBlob(result)
  } catch (error) {
    localFailure(error, '文件下载失败，请重试')
  } finally {
    if (!disposed) busy.value = false
  }
}
function visible() {
  if (document.hidden) {
    clearTimeout(timer)
    controller?.abort()
  } else if (job.value) refresh()
}
onMounted(() => {
  document.addEventListener('visibilitychange', visible)
  if (job.value) refresh()
})
// 卸载后停止状态轮询、创建和下载请求，保留会话存储中的任务 ID 供下次恢复。
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  controller?.abort()
  actionController?.abort()
  document.removeEventListener('visibilitychange', visible)
})
</script>
<template>
  <span class="export-actions">
    <el-button :loading="busy" @click="create">{{
      uncertain ? '重试上次导出' : '导出 CSV'
    }}</el-button>
    <span v-if="uncertain" role="status">上次请求结果未确认，重试将沿用原筛选条件</span>
    <span v-if="pending()" role="status">导出处理中，可离开页面</span>
    <span v-if="state === 'PAUSED'" role="status">后台导出已暂停，可直接导出当前筛选</span>
    <el-button v-if="state === 'DONE'" :loading="busy" @click="download"
      >下载文件（保留24小时）</el-button
    >
    <span v-if="state === 'EXPIRED'" role="status">文件已过期，请重新导出</span>
    <span v-if="['DEAD', 'DEAD_PENDING'].includes(state)" role="alert"
      >导出失败，请联系管理员检查后台任务</span
    >
    <span v-if="failed" role="alert">导出状态更新失败</span>
    <el-button v-if="failed" @click="refresh(false)">重试获取导出状态</el-button>
  </span>
</template>
<style scoped>
.export-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
</style>
