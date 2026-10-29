import { h } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'

export function showRequestError(message, requestId) {
  if (!requestId || !/^[a-zA-Z0-9-]{1,64}$/.test(requestId)) return ElMessage.error(message)
  ElNotification({
    title: '请求失败', type: 'error', duration: 8000,
    message: h('div', { style: 'overflow-wrap:anywhere' }, [
      h('p', message), h('p', `请求 ID：${requestId}`),
      h('button', { type: 'button', onClick: async () => {
        try { await navigator.clipboard.writeText(requestId); ElMessage.success('请求 ID 已复制') }
        catch { ElMessage.warning('请选中请求 ID 手动复制') }
      } }, '复制请求 ID'),
    ]),
  })
}
