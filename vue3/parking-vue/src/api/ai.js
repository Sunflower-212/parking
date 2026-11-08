import request from '@/utils/request'
import { baseURL, clearUnauthorizedSession } from '@/utils/request'
import { useTokenStore } from '@/stores/token'
import { showRequestError, safeMessage } from '@/utils/request-error-notice'
import { readAiStream } from '@/utils/ai-stream'

async function fetchAi(path, options = {}) {
  const response = await fetch(`${baseURL}/ai/${path}`, {
    ...options, headers: { Authorization: useTokenStore().token, ...options.headers },
  })
  if (response.status === 401) clearUnauthorizedSession()
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream') && options.method === 'POST') {
    const data = await response.json().catch(() => ({}))
    const error = new Error(safeMessage(data.message, response.status === 429 ? 'AI助手繁忙，请稍后重试' : 'AI服务暂不可用，请稍后重试'))
    error.requestId = response.headers.get('x-request-id')
    throw error
  }
  return response
}
export async function aiHistory(chatId, signal) {
  try {
    const response = await fetchAi(`history/chat${chatId ? '/' + encodeURIComponent(chatId) : ''}`, { signal })
    const data = await response.json()
    if (!Array.isArray(data)) throw new Error(safeMessage(data.message))
    return { items: data, expired: response.headers.get('x-ai-history-expired') === 'true' }
  } catch (error) {
    if (error.name !== 'AbortError') showRequestError(error.message, error.requestId, error)
    throw error
  }
}
export async function aiChat(input, signal, onEvent) {
  try {
    const response = await fetchAi('chat', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(input), signal })
    await readAiStream(response, event => {
      if (event.type === 'error') { const error = new Error(safeMessage(event.message)); error.requestId = event.requestId || response.headers.get('x-request-id'); throw error }
      onEvent(event)
    })
  } catch (error) {
    if (error.name !== 'AbortError') showRequestError(error.message, error.requestId, error)
    throw error
  }
}

export const aiSessions = () => request.get('/ai/sessions')
export const deleteAiSession = (id) => request.delete(`/ai/sessions/${encodeURIComponent(id)}`)
