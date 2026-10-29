import { h } from 'vue'
import { ElMessage } from 'element-plus'

const handled = new WeakSet()
export function safeMessage(value, fallback = '操作失败，请稍后重试') {
  if (typeof value !== 'string' || !value.trim() || value.length > 240) return fallback
  // Upstream diagnostics belong in logs, including when returned as an API message.
  if (
    /jdbc:|\b(?:select\s+.+\s+from|insert\s+into|update\s+\w+\s+set)\b|(?:Exception|Error):|org\.zjmproject|\bat\s+[\w.$]+\([^)]*:\d+\)|Bearer\s+\S+|eyJ[\w-]+\.[\w-]+\.|sk-[\w-]{16,}|BEGIN.*PRIVATE KEY/i.test(
      value,
    )
  )
    return fallback
  return value.trim()
}
export const feedback = {
  success: (message) => ElMessage({ type: 'success', message, duration: 2000 }),
  warning: (message) => ElMessage({ type: 'warning', message, duration: 4000, showClose: true }),
  info: (message) => ElMessage({ type: 'info', message, duration: 4000, showClose: true }),
  error: (message) => showRequestError(message),
}
export function markHandled(error) {
  if (error && typeof error === 'object') handled.add(error)
}
export function localFailure(error, message) {
  if (error?.code === 'ERR_CANCELED' || (error && typeof error === 'object' && handled.has(error)))
    return
  showRequestError(message, undefined, error)
}
export function showRequestError(message, requestId, error) {
  if (error && typeof error === 'object' && handled.has(error)) return
  markHandled(error)
  const reason = safeMessage(message)
  const validId = typeof requestId === 'string' && /^[a-zA-Z0-9-]{1,64}$/.test(requestId)
  return ElMessage({
    type: 'error',
    duration: 6000,
    showClose: true,
    customClass: 'operation-feedback',
    message: validId
      ? h('div', { class: 'feedback-content' }, [
          h('span', reason),
          h('details', [
            h('summary', '查看详情'),
            h('div', { class: 'feedback-details' }, [
              h('span', `请求 ID：${requestId}`),
              h(
                'button',
                {
                  type: 'button',
                  class: 'feedback-copy',
                  onClick: async () => {
                    try {
                      await navigator.clipboard.writeText(requestId)
                      feedback.success('请求 ID 已复制')
                    } catch {
                      feedback.warning('无法自动复制，请手动复制请求 ID')
                    }
                  },
                },
                '复制请求 ID',
              ),
            ]),
          ]),
        ])
      : reason,
  })
}
