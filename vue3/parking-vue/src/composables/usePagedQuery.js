import { ref, reactive, onScopeDispose } from 'vue'
import request from '@/utils/request'
import { cleanQuery } from '@/utils/format'
export function usePagedQuery(endpoint, filters = {}) {
  const rows = ref([]),
    total = ref(0),
    loading = ref(false),
    error = ref('')
  const pagination = reactive({ pageNum: 1, pageSize: 20 }),
    query = reactive({ ...filters })
  let controller,
    version = 0,
    disposed = false
  async function load(reset = false) {
    if (reset) pagination.pageNum = 1
    controller?.abort()
    controller = new AbortController()
    const current = ++version
    loading.value = true
    error.value = ''
    try {
      const result = await request.get(typeof endpoint === 'function' ? endpoint() : endpoint, {
        params: cleanQuery({ ...query, ...pagination }),
        signal: controller.signal,
      })
      if (disposed || current !== version) return
      const data = result.data
      rows.value = Array.isArray(data) ? data : data?.items || []
      total.value = Array.isArray(data) ? data.length : Number(data?.total || 0)
      if (!rows.value.length && total.value > 0 && pagination.pageNum > 1) {
        pagination.pageNum = Math.max(1, Math.ceil(total.value / pagination.pageSize))
        await load()
      }
    } catch (e) {
      if (!disposed && current === version && e.code !== 'ERR_CANCELED')
        error.value = e.message || '加载失败，请重试'
    } finally {
      if (current === version) loading.value = false
    }
  }
  function reset() {
    Object.assign(query, filters)
    return load(true)
  }
  onScopeDispose(() => {
    disposed = true
    version++
    controller?.abort()
  })
  return { rows, total, loading, error, pagination, query, load, reset }
}
