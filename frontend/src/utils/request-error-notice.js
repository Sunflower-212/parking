import { shallowRef } from 'vue'
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
export const failureAlerts = shallowRef([])
const timers = new Map()
let nextId = 0
function messageOffset() {
  return Math.max(
    16,
    (typeof document === 'undefined'
      ? 0
      : document.querySelector('.failure-alerts')?.getBoundingClientRect().bottom || 0) + 16,
  )
}
export function closeFailure(id) {
  clearTimeout(timers.get(id))
  timers.delete(id)
  failureAlerts.value = failureAlerts.value.filter((alert) => alert.id !== id)
}
export const feedback = {
  success: (message) => {
    failureAlerts.value.forEach((alert) => closeFailure(alert.id))
    return ElMessage({ type: 'success', message, duration: 2000 })
  },
  warning: (message) =>
    ElMessage({
      type: 'warning',
      message,
      duration: 4000,
      showClose: true,
      offset: messageOffset(),
    }),
  info: (message) =>
    ElMessage({ type: 'info', message, duration: 4000, showClose: true, offset: messageOffset() }),
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
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  ElMessage.closeAll?.()
  const id = ++nextId
  const validId = typeof requestId === 'string' && /^[a-zA-Z0-9-]{1,64}$/.test(requestId)
  failureAlerts.value = [
    ...failureAlerts.value,
    { id, message: safeMessage(message), requestId: validId ? requestId : null },
  ]
  timers.set(
    id,
    setTimeout(() => closeFailure(id), 6000),
  )
  return { close: () => closeFailure(id) }
}
