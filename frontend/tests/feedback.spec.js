import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { Blob as NodeBlob } from 'node:buffer'
import { ElMessage } from 'element-plus'
import request from '../src/utils/request'
import {
  feedback,
  localFailure,
  showRequestError,
  safeMessage,
  failureAlerts,
  closeFailure,
} from '../src/utils/request-error-notice'
const mocks = vi.hoisted(() => ({ removeToken: vi.fn(), removeInfo: vi.fn(), replace: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))
vi.mock('../src/router', () => ({
  default: { currentRoute: { value: { path: '/user/account' } }, replace: mocks.replace },
}))
vi.mock('../src/stores/token', () => ({
  useTokenStore: () => ({ token: 'test-token', removeToken: mocks.removeToken }),
}))
vi.mock('../src/stores/userInfo', () => ({ default: () => ({ removeInfo: mocks.removeInfo }) }))
vi.mock('../src/stores/adminInfo', () => ({ default: () => ({ removeInfo: mocks.removeInfo }) }))
beforeEach(() => {
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  vi.clearAllMocks()
  vi.stubGlobal('Blob', NodeBlob)
})
afterEach(() => {
  failureAlerts.value.forEach((alert) => closeFailure(alert.id))
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
function response(data, status = 200) {
  request.defaults.adapter = async (config) => ({
    data,
    status,
    statusText: '',
    headers: { 'x-request-id': 'feedback-test' },
    config,
  })
}
function failure(status, data = { message: '测试业务原因' }, code) {
  request.defaults.adapter = async (config) => {
    throw Object.assign(new Error('transport error'), {
      config,
      code,
      response: status ? { status, data, headers: { 'x-request-id': 'feedback-test' } } : undefined,
    })
  }
}
it('uses explicit lifetimes and keeps request ID in native expandable details', () => {
  feedback.success('保存成功')
  expect(ElMessage).toHaveBeenLastCalledWith(
    expect.objectContaining({ type: 'success', duration: 2000 }),
  )
  feedback.warning('请重试')
  expect(ElMessage).toHaveBeenLastCalledWith(
    expect.objectContaining({ type: 'warning', duration: 4000 }),
  )
  vi.useFakeTimers()
  showRequestError('保存失败', 'feedback-test')
  expect(failureAlerts.value).toHaveLength(1)
  expect(failureAlerts.value[0]).toMatchObject({ message: '保存失败', requestId: 'feedback-test' })
  vi.advanceTimersByTime(5999)
  expect(failureAlerts.value).toHaveLength(1)
  vi.advanceTimersByTime(1)
  expect(failureAlerts.value).toHaveLength(0)
})
it('reports HTTP 200 business errors exactly once, preserving code and request ID', async () => {
  response({ code: 1, message: '车位已被占用' })
  const error = await request.patch('/enter/reservation', { id: 1 }).catch((e) => e)
  expect(error).toMatchObject({ code: 1, message: '车位已被占用', requestId: 'feedback-test' })
  localFailure(error, '预约失败')
  expect(failureAlerts.value).toHaveLength(1)
})
it.each([403, 409, 503])('reports HTTP %i without clearing the session', async (status) => {
  failure(status)
  await expect(request.get('/test')).rejects.toHaveProperty('response.status', status)
  expect(failureAlerts.value).toHaveLength(1)
  expect(mocks.removeToken).not.toHaveBeenCalled()
})
it('clears a 401 session and collapses concurrent expiry notices', async () => {
  failure(401)
  await Promise.allSettled([request.get('/a'), request.get('/b')])
  expect(mocks.removeToken).toHaveBeenCalled()
  expect(mocks.replace).toHaveBeenCalledWith('/login')
  expect(failureAlerts.value).toHaveLength(1)
})
it.each([
  ['ECONNABORTED', '请求超时'],
  ['ERR_NETWORK', '网络或服务异常'],
])('reports %s', async (code, message) => {
  failure(undefined, undefined, code)
  const error = await request.get('/test').catch((e) => e)
  expect(error.message).toContain(message)
  expect(failureAlerts.value).toHaveLength(1)
})
it('keeps canceled and repeated background failures quiet while allowing manual retry', async () => {
  failure(undefined, undefined, 'ERR_CANCELED')
  await request.get('/test').catch((e) => localFailure(e, '失败'))
  expect(failureAlerts.value).toHaveLength(0)
  failure(503)
  for (let i = 0; i < 3; i++)
    await request.get('/poll', { feedback: false }).catch((e) => localFailure(e, '失败'))
  expect(failureAlerts.value).toHaveLength(0)
  await request.get('/poll').catch(() => {})
  expect(failureAlerts.value).toHaveLength(1)
})
it('rejects JSON Blob errors and keeps valid CSV downloads intact', async () => {
  response(
    new NodeBlob([JSON.stringify({ code: 1, message: '文件已过期' })], {
      type: 'application/json',
    }),
  )
  await expect(request.get('/file', { responseType: 'blob' })).rejects.toHaveProperty(
    'message',
    '文件已过期',
  )
  expect(failureAlerts.value).toHaveLength(1)
  const csv = new NodeBlob(['id,name\n1,test'], { type: 'text/csv' })
  response(csv)
  expect((await request.get('/file', { responseType: 'blob' })).data).toBe(csv)
  failure(
    503,
    new NodeBlob([JSON.stringify({ code: 1, message: '导出服务暂不可用' })], {
      type: 'application/json',
    }),
  )
  await expect(request.get('/file', { responseType: 'blob' })).rejects.toHaveProperty(
    'message',
    '导出服务暂不可用',
  )
})
it('filters backend diagnostics and treats markup as text', async () => {
  for (const text of [
    'SELECT password FROM user',
    'java.lang.Error: secret',
    'Bearer private-token',
    'jdbc:mysql://private',
    'sk-' + '1'.repeat(20),
  ])
    expect(safeMessage(text)).toBe('操作失败，请稍后重试')
  expect(safeMessage('密码错误')).toBe('密码错误')
  showRequestError('<img onerror=alert(1)>', 'test-id')
  expect(failureAlerts.value[0].message).toBe('<img onerror=alert(1)>')
})
