<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import PageShell from '@/components/PageShell.vue'
import SearchBar from '@/components/SearchBar.vue'
import PagedTable from '@/components/PagedTable.vue'
import { usePagedQuery } from '@/composables/usePagedQuery'
import request from '@/utils/request'
import { dateTime } from '@/utils/format'
const { rows, total, loading, error, pagination, query, load, reset } = usePagedQuery('/admin/audit/page', {
  from: null, to: null, actorId: null, action: null, result: null, requestId: null,
})
const actions = {
  'parking.create': '新增停车场', 'parking.update': '修改停车场及归属', 'parking.delete': '删除停车场',
  'layout.add': '新增车位', 'layout.update': '修改地图', 'layout.delete': '删除车位',
  'rule.create': '新增计费规则', 'rule.update': '修改计费规则', 'rule.delete': '删除计费规则',
  'user.create': '新增用户', 'user.delete': '删除用户', 'user.password_reset': '重置用户密码',
  'admin.create': '新增管理员', 'admin.delete': '删除管理员', 'admin.password_reset': '重置管理员密码',
  'admin.password_change': '管理员修改密码', 'admin.login': '管理员登录', 'admin.logout': '管理员退出',
  'export.create': '导出受理', 'export.complete': '文件就绪', 'export.download': '下载/同步导出',
}
const outcomes = { SUCCESS: '成功', FAILURE: '失败', ERROR: '异常', TIMEOUT: '超时' }
const dialog = ref(false), detail = ref(null), detailLoading = ref(false), detailError = ref('')
let detailController, version = 0, disposed = false, selectedId
function page(value) { pagination.pageNum = value; load() }
function size(value) { pagination.pageSize = value; load(true) }
async function open(row) {
  selectedId = row.eventId; detailController?.abort(); detailController = new AbortController()
  const current = ++version
  dialog.value = true; detail.value = null; detailLoading.value = true; detailError.value = ''
  try {
    const response = await request.get(`/admin/audit/${row.eventId}`, { signal: detailController.signal })
    if (!disposed && current === version) detail.value = response.data
  } catch (e) {
    if (!disposed && current === version && e.code !== 'ERR_CANCELED') detailError.value = '详情加载失败，请重试'
  } finally { if (!disposed && current === version) detailLoading.value = false }
}
function close() { version++; detailController?.abort(); detailLoading.value = false }
async function copy(value) {
  try { await navigator.clipboard.writeText(value); ElMessage.success('请求 ID 已复制') }
  catch { ElMessage.warning('无法自动复制，请选中请求 ID 手动复制') }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; close() })
</script>
<template>
  <PageShell title="操作日志" description="管理操作与管理员登录记录；仅超级管理员可查看。默认查询最近7天，单次最多31天，时间为北京时间。">
    <el-alert title="消息模式下记录可能延迟出现；日志写入故障时，登录及失败操作记录可能缺失。历史字段未采集时显示说明。" type="info" :closable="false" show-icon />
    <SearchBar @search="load(true)" @reset="reset">
      <el-form-item label="开始"><el-date-picker v-model="query.from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="最近7天" /></el-form-item>
      <el-form-item label="结束"><el-date-picker v-model="query.to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="现在" /></el-form-item>
      <el-form-item label="操作人ID"><el-input-number v-model="query.actorId" :min="1" :precision="0" :controls="false" /></el-form-item>
      <el-form-item label="操作"><el-select v-model="query.action" clearable placeholder="全部操作" style="width:200px"><el-option v-for="(label, value) in actions" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="结果"><el-select v-model="query.result" clearable placeholder="全部" style="width:120px"><el-option v-for="(label,value) in outcomes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="请求ID"><el-input v-model="query.requestId" maxlength="64" clearable placeholder="精确请求 ID" /></el-form-item>
    </SearchBar>
    <PagedTable :rows="rows" :total="total" :loading="loading" :error="error" :pagination="pagination" row-key="eventId" @reload="load()" @page="page" @size="size">
      <el-table-column label="发生时间" min-width="180"><template #default="{row}">{{ dateTime(row.occurredAt) }}</template></el-table-column>
      <el-table-column label="操作人ID" min-width="100"><template #default="{row}">{{ row.actorId ?? '未识别' }}</template></el-table-column>
      <el-table-column label="操作" min-width="180"><template #default="{row}">{{ actions[row.action] || row.action }}</template></el-table-column>
      <el-table-column label="结果" min-width="160"><template #default="{row}"><el-tag :type="row.result === 'SUCCESS' ? 'success' : row.result ? 'danger' : 'info'">{{ outcomes[row.result] || '历史记录未采集' }}</el-tag></template></el-table-column>
      <el-table-column prop="requestId" label="请求ID" min-width="280" show-overflow-tooltip />
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" @click="open(row)">详情</el-button></template></el-table-column>
    </PagedTable>
    <el-dialog v-model="dialog" title="操作日志详情" width="640px" @close="close">
      <div v-loading="detailLoading" class="audit-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button @click="open({eventId:selectedId})">重试</el-button></el-alert>
        <dl v-if="detail">
          <dt>事件ID</dt><dd>{{ detail.eventId }}</dd><dt>操作</dt><dd>{{ actions[detail.action] || detail.action }}</dd>
          <dt>结果</dt><dd>{{ outcomes[detail.result] || '历史记录未采集' }}</dd>
          <dt>操作人 / 角色</dt><dd>{{ detail.actorId ?? '未识别' }} / {{ detail.actorRole === 1 ? '超级管理员' : detail.actorRole === 0 ? '管理员' : '历史记录未采集' }}</dd>
          <dt>目标ID</dt><dd>{{ detail.entityIds }}</dd><dt>发生时间</dt><dd>{{ dateTime(detail.occurredAt) }}</dd>
          <dt>原因码</dt><dd>{{ detail.reasonCode || '无 / 历史记录未采集' }}</dd>
          <dt>请求ID</dt><dd>{{ detail.requestId || '历史记录未采集' }} <el-button v-if="detail.requestId" link type="primary" @click="copy(detail.requestId)">复制</el-button></dd>
        </dl>
      </div>
    </el-dialog>
  </PageShell>
</template>
<style scoped>
.audit-detail { min-height: 100px; overflow-wrap: anywhere; }
dt { font-weight: 600; margin-top: 16px; }
dd { margin: 6px 0 0; color: #64748b; }
</style>
