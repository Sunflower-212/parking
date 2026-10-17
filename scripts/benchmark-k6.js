import http from 'k6/http'
import exec from 'k6/execution'
import { Counter, Rate, Trend } from 'k6/metrics'

const base = 'http://backend:8080'
const warmup = Number(__ENV.WARMUP || 120)
const seconds = Number(__ENV.SECONDS || 300)
const clients = Number(__ENV.CLIENTS || 100)
const mode = __ENV.MODE || 'mixed'
const original = __ENV.VERSION === 'original'
const latency = Object.fromEntries(['dashboard', 'orders', 'floor', 'reservation', 'cancellation'].map(name => [name, new Trend('measured_' + name, true)]))
const requests = new Counter('measured_requests')
const reads = new Counter('measured_reads')
const writes = new Counter('measured_writes')
const failures = new Rate('measured_errors')
const conflicts = new Counter('measured_conflicts')
const inFlight = new Trend('measured_wall_ms', true)
let rng = 0
export const options = {
  scenarios: mode === 'mixed'
    ? { load: { executor: 'constant-vus', vus: clients, duration: `${warmup + seconds}s`, gracefulStop: '35s' } }
    : { probe: { executor: 'per-vu-iterations', vus: clients, iterations: 1, maxDuration: '60s' } },
  setupTimeout: '120s',
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  systemTags: ['status', 'method', 'name', 'scenario'],
  thresholds: { measured_errors: ['rate<0.001'] },
}
function json(response) { try { return response.json() } catch { return null } }
function login(name, password, admin = false) {
  const response = http.post(base + (admin ? '/admin/login' : '/user/login'), { username: name, password }, { tags: { name: 'setup-login' }, timeout: '30s' })
  const body = json(response)
  if (response.status !== 200 || body?.code !== 0) throw new Error('Isolated fixture login failed')
  return body.data
}
export function setup() {
  const admin = login('benchmark', __ENV.BENCH_ADMIN_PASSWORD, true)
  const tokens = []
  for (let i = 0; i < clients; i++) tokens.push(login('bench' + String(i + 1).padStart(6, '0'), 'test12345'))
  return { admin, tokens }
}
function random() {
  if (!rng) rng = (20261017 + __VU * 7919 + Number(__ENV.ROUND || 1) * 1000) >>> 0
  rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5
  return (rng >>> 0) / 4294967296
}
function call(group, path, token, method = 'GET', payload = null) {
  const start = Date.now()
  const response = http.request(method, base + path, payload === null ? null : JSON.stringify(payload), {
    headers: { Authorization: token, 'Content-Type': 'application/json' },
    tags: { name: group }, timeout: '30s',
  })
  const body = json(response)
  const elapsed = Date.now() - exec.scenario.startTime
  const measured = mode !== 'mixed' || (elapsed >= warmup * 1000 && elapsed < (warmup + seconds) * 1000)
  if (measured) {
    requests.add(1)
    if (method === 'GET') reads.add(1)
    else writes.add(1)
    failures.add(response.status !== 200 || body?.code !== 0)
    latency[group].add(response.timings.duration)
    inFlight.add(Date.now() - start)
  }
  return { response, body }
}
export default function(data) {
  const token = data.tokens[__VU - 1]
  if (mode !== 'mixed') {
    call(mode === 'floor' ? 'floor' : 'dashboard', mode === 'floor' ? (original ? '/spaces/get?park_id=1&spaces_storey=1' : '/spaces/availability?park_id=1&spaces_storey=1') : original ? '/orders/allData' : '/dashboard/summary', mode === 'floor' ? token : data.admin)
    return
  }
  const choice = random()
  if (choice < .2) call('dashboard', original ? '/orders/allData' : '/dashboard/summary', data.admin)
  else if (choice < .5) call('orders', original ? '/orders/userList' : '/orders/userPageList?pageNum=1&pageSize=20', token)
  else if (choice < .8) call('floor', original ? '/spaces/get?park_id=1&spaces_storey=1' : '/spaces/availability?park_id=1&spaces_storey=1', token)
  else {
    const result = call('reservation', '/enter/reservation', token, 'PATCH', { id: __VU })
    if (result.response.status === 200 && result.body?.code === 0) call('cancellation', '/enter/cancelReservation?id=' + result.body.data.id, token, 'POST')
  }
}
export function teardown(data) {
  const names = ['http.server.requests', 'hikaricp.connections.acquire', 'hikaricp.connections.timeout', 'parking.cache.hit', 'parking.cache.miss', 'parking.cache.local.hit', 'parking.cache.redis.hit', 'parking.cache.redis.duration', 'parking.cache.codec.duration', 'parking.cache.load.duration', 'parking.cache.singleflight.wait', 'parking.flow.lock.acquire', 'parking.flow.park.lock.hold', 'parking.admission.wait', 'parking.reservation.outbox.pending', 'parking.reservation.dispatch.failure']
  const metrics = {}
  const catalog = http.get(base + '/actuator/metrics', { headers: { Authorization: data.admin }, tags: { name: 'telemetry' }, timeout: '5s' })
  const available = json(catalog)?.names || []
  for (const name of names) {
    if (!available.includes(name)) continue
    const response = http.get(base + '/actuator/metrics/' + name, { headers: { Authorization: data.admin }, tags: { name: 'telemetry' }, timeout: '5s' })
    const value = json(response)
    if (response.status === 200 && value?.measurements) metrics[name] = Object.fromEntries(value.measurements.map(item => [item.statistic, item.value]))
  }
  console.log('SERVER_METRICS=' + JSON.stringify(metrics))
}
export function handleSummary(data) {
  const measured = data.metrics.measured_requests?.values.count || 0
  const summary = {
    version: __ENV.VERSION, mode, round: Number(__ENV.ROUND || 1), clients, warmup, seconds,
    requests: measured, rps: mode === 'mixed' ? measured / seconds : null,
    reads: data.metrics.measured_reads?.values.count || 0,
    writes: data.metrics.measured_writes?.values.count || 0,
    errors: { count: data.metrics.measured_errors?.values.passes || 0, successful: data.metrics.measured_errors?.values.fails || 0, rate: data.metrics.measured_errors?.values.rate || 0 },
    groups: Object.fromEntries(Object.keys(latency).filter(name => data.metrics['measured_' + name]).map(name => [name, data.metrics['measured_' + name].values])),
    wall: data.metrics.measured_wall_ms?.values, metrics: data.metrics,
  }
  return { [__ENV.RESULT_PATH || '/results/result.json']: JSON.stringify(summary, null, 2), stdout: JSON.stringify({ ...summary, metrics: undefined }) + '\n' }
}
