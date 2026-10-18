import http from 'k6/http'
import { Rate } from 'k6/metrics'
import * as workload from './benchmark-k6.js'
const submissionErrors = new Rate('background_submission_errors')
export const options = {
  ...workload.options,
  scenarios: {
    ...workload.options.scenarios,
    exporter: { executor: 'per-vu-iterations', exec: 'exportUsers', vus: 1, iterations: 1, startTime: `${Number(__ENV.WARMUP || 120) + 10}s`, maxDuration: '30s' },
  },
  thresholds: { ...workload.options.thresholds, background_submission_errors: ['rate<0.001'] },
}
export function setup() {
  const data = workload.setup()
  // Scenario VU IDs are global. Provision a unique user for every possible load VU ID.
  const response = http.post('http://backend:8080/user/login', { username: 'bench000101', password: 'test12345' }, { timeout: '30s', tags: { name: 'setup-login' } })
  let body; try { body = response.json() } catch { }
  if (response.status !== 200 || body?.code !== 0) throw new Error('Additional scenario user setup failed')
  data.tokens.push(body.data)
  return data
}
export const teardown = workload.teardown
export const handleSummary = workload.handleSummary
export default function(data) { return workload.default(data) }
export function exportUsers(data) {
  const response = http.post('http://backend:8080/admin/background/exports', '{}', { headers: { Authorization: data.admin, 'Content-Type': 'application/json', 'Idempotency-Key': `benchmark-${__ENV.ROUND}-${Date.now()}` }, timeout: '25s', tags: { name: 'background-export-submit' } })
  let body; try { body = response.json() } catch { }
  const good = response.status === 200 && body?.code === 0 && typeof body?.data?.id === 'string'
  submissionErrors.add(!good)
  if (good) console.log('BACKGROUND_EXPORT=' + JSON.stringify({ id: body.data.id, acceptance_ms: response.timings.duration }))
}
