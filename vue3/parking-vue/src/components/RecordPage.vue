<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { jwtDecode } from 'jwt-decode'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import PageShell from './PageShell.vue'
import SearchBar from './SearchBar.vue'
import PagedTable from './PagedTable.vue'
import StatusTag from './StatusTag.vue'
import ExportUsers from './ExportUsers.vue'
import { pages } from '@/config/pages'
import { usePagedQuery } from '@/composables/usePagedQuery'
import { useTokenStore } from '@/stores/token'
import request from '@/utils/request'
import { dateTime, money, cleanQuery } from '@/utils/format'
const props = defineProps({ page: { type: String, required: true } }),
  config = pages[props.page]
const role = jwtDecode(useTokenStore().token).claims.permissions
const endpoint = (value) => (typeof value === 'function' ? value(role) : value)
const { rows, total, loading, error, pagination, query, load, reset } = usePagedQuery(
  () => endpoint(config.endpoint),
  Object.fromEntries(config.filters.map((f) => [f.key, null])),
)
const dialog = ref(false),
  editing = ref(false),
  submitting = ref(false),
  model = ref({}),
  form = ref(),
  sources = ref({}),
  sourcesLoading = ref(props.page === 'parks'),
  sourcesError = ref('')
let sourcesController,
  disposed = false
const canEdit = computed(() => !config.rootEdit || role === 1)
function changePage(value) {
  pagination.pageNum = value
  load()
}
function changeSize(value) {
  pagination.pageSize = value
  load(true)
}
const fields = computed(() => (config.fields || []).filter((f) => !f.rootOnly || role === 1))
function open(row) {
  editing.value = !!row
  model.value = row
    ? { ...row }
    : Object.fromEntries(
        fields.value.map((f) => [
          f.key,
          f.type === 'number' ? f.min || 0 : f.options ? f.options[0].value : null,
        ]),
      )
  dialog.value = true
}
async function save() {
  if (
    submitting.value ||
    sourcesLoading.value ||
    sourcesError.value ||
    !(await form.value.validate().catch(() => false))
  )
    return
  submitting.value = true
  try {
    await request({
      url: endpoint(editing.value ? config.update : config.create),
      method: editing.value ? 'put' : 'post',
      data: model.value,
    })
    dialog.value = false
    const refreshed = await load(!editing.value, { feedback: false })
    if (!disposed)
      refreshed
        ? ElMessage.success('保存成功')
        : ElMessage.warning('保存成功，列表刷新失败，请刷新列表')
  } catch (error) {
    localFailure(error, '保存失败，请重试')
  } finally {
    submitting.value = false
  }
}
async function action(row, type) {
  try {
    await ElMessageBox.confirm(
      type === 'reset'
        ? '确认重置为初始密码 123456？用户需重新登录。'
        : '确认删除该记录？存在关联业务记录时将无法删除。',
      type === 'reset' ? '重置密码' : '删除记录',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    if (type === 'reset')
      await request.patch(config.reset, { [config.identity]: row[config.identity] })
    else
      await request.delete(config.remove, { params: { [config.identity]: row[config.identity] } })
    const message = type === 'reset' ? '密码已重置' : '删除成功'
    const refreshed = await load(false, { feedback: false })
    if (!disposed)
      refreshed
        ? ElMessage.success(message)
        : ElMessage.warning(`${message}，列表刷新失败，请刷新列表`)
  } catch (error) {
    localFailure(error, type === 'reset' ? '密码重置失败，请重试' : '删除失败，请重试')
  } finally {
    submitting.value = false
  }
}
// 规则与管理员选项统一加载；仅超级管理员请求管理员列表，加载失败时阻止提交不完整关联。
async function loadSources() {
  if (props.page === 'parks') {
    sourcesController?.abort()
    const controller = new AbortController()
    sourcesController = controller
    sourcesLoading.value = true
    sourcesError.value = ''
    try {
      const results = await Promise.all([
        request.get('/rule/select', { signal: controller.signal }),
        role === 1
          ? request.get('/admin/list', { signal: controller.signal })
          : Promise.resolve({ data: [] }),
      ])
      // 重复加载下拉选项时，仅最后一次请求可写入状态，卸载后的结果直接丢弃。
      if (disposed || controller !== sourcesController) return
      sources.value = {
        rules: results[0].data.map((r) => ({ value: r.id, label: r.rule_name })),
        admins: results[1].data.map((a) => ({ value: a.id, label: a.admin_name })),
      }
    } catch (error) {
      if (!disposed && controller === sourcesController && error.code !== 'ERR_CANCELED')
        sourcesError.value = '停车场基础选项加载失败，请重试'
    } finally {
      if (!disposed && controller === sourcesController) sourcesLoading.value = false
    }
  }
}
onMounted(() => {
  load()
  loadSources()
})
onBeforeUnmount(() => {
  disposed = true
  sourcesController?.abort()
})
</script>
<template>
  <PageShell :title="config.title"
    ><template #actions
      ><ExportUsers v-if="config.export" :filters="query" />
      <el-button
        v-if="config.create"
        type="primary"
        :disabled="sourcesLoading || !!sourcesError"
        @click="open()"
        >新增{{ config.title.replace('管理', '') }}</el-button
      ><el-button @click="load()" :loading="loading">刷新</el-button></template
    ><el-alert v-if="sourcesError" :title="sourcesError" type="error" :closable="false"
      ><el-button link @click="loadSources">重新加载选项</el-button></el-alert
    >
    <SearchBar @search="load(true)" @reset="reset"
      ><el-form-item v-for="field in config.filters" :key="field.key" :label="field.label"
        ><el-select
          v-if="field.options"
          v-model="query[field.key]"
          clearable
          :aria-label="field.label"
          ><el-option
            v-for="o in field.options"
            :key="o.value"
            :label="o.label"
            :value="o.value" /></el-select
        ><el-date-picker
          v-else-if="['date', 'datetime'].includes(field.type)"
          v-model="query[field.key]"
          :type="field.type"
          :value-format="field.type === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
          :placeholder="field.label" /><el-input
          v-else
          v-model="query[field.key]"
          clearable
          :placeholder="'请输入' + field.label"
          :aria-label="field.label" /></el-form-item></SearchBar
    ><PagedTable
      :rows="rows"
      :total="total"
      :loading="loading"
      :error="error"
      :pagination="pagination"
      @reload="load()"
      @page="changePage"
      @size="changeSize"
      ><el-table-column
        v-for="col in config.columns"
        :key="col.key"
        :prop="col.key"
        :label="col.label"
        :min-width="col.date ? 170 : col.key === 'park_address' ? 180 : 110"
        show-overflow-tooltip
        ><template #default="{ row }"
          ><StatusTag
            v-if="col.kind"
            :value="row[col.key]"
            :kind="col.kind"
            :reservation="col.kind === 'entry' ? row.reservation : undefined"
          /><span v-else>{{
            col.date
              ? dateTime(row[col.key])
              : col.money
                ? money(row[col.key])
                : (row[col.key] ?? '—')
          }}</span></template
        ></el-table-column
      ><el-table-column
        v-if="(config.update || config.remove || config.reset) && canEdit"
        label="操作"
        :min-width="config.reset ? 170 : 130"
        fixed="right"
        ><template #default="{ row }"
          ><el-button
            v-if="config.update"
            link
            type="primary"
            :disabled="sourcesLoading || !!sourcesError"
            @click="open(row)"
            >编辑</el-button
          ><el-button
            v-if="config.reset"
            link
            type="primary"
            :disabled="submitting"
            @click="action(row, 'reset')"
            >重置密码</el-button
          ><el-button
            v-if="config.remove"
            link
            type="danger"
            :disabled="submitting"
            @click="action(row, 'delete')"
            >删除</el-button
          ></template
        ></el-table-column
      ></PagedTable
    ><el-dialog
      v-model="dialog"
      :title="editing ? '编辑记录' : '新增记录'"
      width="560px"
      :close-on-click-modal="!submitting"
      destroy-on-close
      ><el-alert
        v-if="page === 'users' || page === 'admins'"
        title="新账号初始密码与用户名相同，请及时修改。"
        type="info"
        :closable="false"
      /><el-form ref="form" :model="model" label-position="top" @submit.prevent="save"
        ><div class="form-grid">
          <el-form-item
            v-for="field in fields"
            :key="field.key"
            :label="field.label"
            :prop="field.key"
            :rules="[
              {
                required:
                  field.required || field.type === 'number' || !!field.source || !!field.options,
                message: '请填写' + field.label,
                trigger: 'blur',
              },
              ...(field.minlength
                ? [
                    {
                      min: field.minlength,
                      max: field.maxlength,
                      message: `长度为${field.minlength}至${field.maxlength}位`,
                      trigger: 'blur',
                    },
                  ]
                : []),
            ]"
            ><el-select
              v-if="field.options || field.source"
              v-model="model[field.key]"
              filterable
              :aria-label="field.label"
              ><el-option
                v-for="o in field.options || sources[field.source] || []"
                :key="o.value"
                :label="o.label"
                :value="o.value" /></el-select
            ><el-input-number
              v-else-if="field.type === 'number'"
              v-model="model[field.key]"
              :min="field.min"
              :max="field.key === 'park_storey' ? 100 : 100000"
              :precision="field.precision || 0" /><el-input
              v-else
              v-model="model[field.key]"
              :maxlength="field.maxlength || 100"
              :disabled="editing && ['park_name'].includes(field.key)"
              :aria-label="field.label"
          /></el-form-item></div></el-form
      ><template #footer
        ><el-button @click="dialog = false" :disabled="submitting">取消</el-button
        ><el-button
          type="primary"
          :loading="submitting"
          :disabled="sourcesLoading || !!sourcesError"
          @click="save"
          >保存</el-button
        ></template
      ></el-dialog
    ></PageShell
  >
</template>
