import test from 'node:test'
import assert from 'node:assert/strict'
import { httpErrorMessage } from '../src/utils/http-errors.js'
test('network errors without a response do not throw', () => {
  assert.match(httpErrorMessage(new Error('offline')), /网络/)
  assert.match(httpErrorMessage(null), /网络/)
})
test('authorization and timeout errors remain distinguishable', () => {
  assert.match(httpErrorMessage({ response: { status: 401 } }), /重新登录/)
  assert.match(httpErrorMessage({ response: { status: 403 } }), /权限/)
  assert.match(httpErrorMessage({ code: 'ECONNABORTED' }), /超时/)
})
