import http from 'k6/http'
import exec from 'k6/execution'
import { Counter, Rate, Trend } from 'k6/metrics'

const base = 'http://backend:8080'
const warmup = Number(__ENV.WARMUP || 120)
const seconds = Number(__ENV.SECONDS || 300)
const clients = Number(__ENV.CLIENTS || 100)
const mode = __ENV.MODE || 'mixed'
const profile = __ENV.PROFILE || 'mixed'
const original = __ENV.VERSION === 'original'
const latency = Object.fromEntries(['dashboard', 'orders', 'floor', 'reservation', 'cancellation'].map(name => [name, new Trend('measured_' + name, true)]))
const requests = new Counter('measured_requests')
const reads = new Counter('measured_reads')
const writes = new Counter('measured_writes')
const failures = new Rate('measured_errors')
const conflicts = new Counter('measured_conflicts')
const rejected = new Counter('measured_rejected')
const succeeded = new Counter('measured_successful_requests')
const reserved = new Counter('measured_committed_reservations')
const cancelled = new Counter('measured_committed_cancellations')
const inFlight = new Trend('measured_wall_ms', true)
let rng = 0
export const options = {
  scenarios: mode === 'mixed'
    ? { load: profile === 'arrival'
      ? { executor: 'ramping-arrival-rate', startRate: 50, timeUnit: '1s', preAllocatedVUs: clients, maxVUs: 400, stages: [{duration: `${warmup}s`,target: 100}, {duration: `${seconds / 3}s`,target: 200}, {duration: `${seconds / 3}s`,target: 400}, {duration: `${seconds / 3}s`,target: 600}], gracefulStop: '35s' }
      : { executor: 'constant-vus', vus: clients, duration: `${warmup + seconds}s`, gracefulStop: '35s' } }
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
  for (let i = 0; i < (profile === 'arrival' ? 400 : profile === 'soak' ? clients + 20 : clients); i++) tokens.push(login('bench' + String(i + 1).padStart(6, '0'), 'test12345'))
  if (profile === 'soak') {
    for (let i = 0; i < 20; i++) {
      const response = http.patch(base + '/enter/reservation', JSON.stringify({id: i + 1}), {headers: {Authorization: tokens[i], 'Content-Type': 'application/json'}, timeout: '30s', tags: {name: 'setup-held-reservation'}})
      if (response.status !== 200 || json(response)?.code !== 0) throw new Error('Soak expiry cohort could not reserve')
    }
  }
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
    const success = response.status === 200 && body?.code === 0
    const conflict = profile === 'contention' && group === 'reservation' && response.status === 200 && body?.code === 1 && /该车位不可预约|车位已被占用|您已有预约或车辆在场/.test(body?.message || '')
    const overload = response.status === 503 && /停车场请求较多/.test(body?.message || '')
    failures.add(!success && !conflict && !overload)
    conflicts.add(conflict ? 1 : 0); rejected.add(overload ? 1 : 0); succeeded.add(success ? 1 : 0)
    if (success && group === 'reservation') reserved.add(1)
    if (success && group === 'cancellation') cancelled.add(1)
    latency[group].add(response.timings.duration)
    inFlight.add(Date.now() - start)
  }
  return { response, body }
}
export default function(data) {
  const token = data.tokens[__VU - 1 + (profile === 'soak' ? 20 : 0)]
  const parking = profile === 'multi' ? ((__VU - 1) % 100 + 1) : 1
  const space = profile === 'contention' ? 1 : profile === 'multi' ? (parking - 1) * 1000 + 1 : __VU + (profile === 'soak' ? 20 : 0)
  if (mode !== 'mixed') {
    call(mode === 'floor' ? 'floor' : 'dashboard', mode === 'floor' ? (original ? '/spaces/get?park_id=1&spaces_storey=1' : '/spaces/availability?park_id=1&spaces_storey=1') : original ? '/orders/allData' : '/dashboard/summary', mode === 'floor' ? token : data.admin)
    return
  }
  const choice = random()
  if (choice < .2) call('dashboard', original ? '/orders/allData' : '/dashboard/summary', data.admin)
  else if (choice < .5) call('orders', original ? '/orders/userList' : '/orders/userPageList?pageNum=1&pageSize=20', token)
  else if (choice < .8) call('floor', original ? `/spaces/get?park_id=${parking}&spaces_storey=1` : `/spaces/availability?park_id=${parking}&spaces_storey=1`, token)
  else {
    const result = call('reservation', '/enter/reservation', token, 'PATCH', { id: space })
    if (result.response.status === 200 && result.body?.code === 0) call('cancellation', '/enter/cancelReservation?id=' + result.body.data.id, token, 'POST')
  }
}
export function teardown(data) {
  if (profile === 'soak') {
    let released = 0
    for (let i = 0; i < 20; i++) {
      const response = http.get(base + '/enter/userEnterOrReservation', {headers: {Authorization: data.tokens[i]}, timeout: '10s', tags: {name: 'expiry-probe'}})
      const body = json(response)
      if (response.status === 200 && body?.code === 0 && body.data === null) released++
    }
    console.log('EXPIRY_PROBE=' + JSON.stringify({held: 20, released}))
  }
  const names = ['http.server.requests', 'hikaricp.connections.acquire', 'hikaricp.connections.timeout', 'parking.cache.hit', 'parking.cache.miss', 'parking.cache.local.hit', 'parking.cache.redis.hit', 'parking.cache.redis.duration', 'parking.cache.codec.duration', 'parking.cache.load.duration', 'parking.cache.singleflight.wait', 'parking.flow.lock.acquire', 'parking.flow.park.lock.hold', 'parking.admission.wait', 'parking.admission.rejected', 'parking.flow.capacity.update', 'parking.flow.commit.window', 'parking.flow.transaction.duration', 'parking.flow.transaction.retry', 'parking.reservation.outbox.pending', 'parking.reservation.dispatch.failure']
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
    version: __ENV.VERSION, mode, profile, round: Number(__ENV.ROUND || 1), clients, warmup, seconds,
    conflicts: data.metrics.measured_conflicts?.values.count || 0,
    rejected: data.metrics.measured_rejected?.values.count || 0,
    successfulRequests: data.metrics.measured_successful_requests?.values.count || 0,
    committedReservations: data.metrics.measured_committed_reservations?.values.count || 0,
    committedCancellations: data.metrics.measured_committed_cancellations?.values.count || 0,
    droppedIterations: data.metrics.dropped_iterations?.values.count || 0,
    requests: measured, rps: mode === 'mixed' ? measured / seconds : null,
    reads: data.metrics.measured_reads?.values.count || 0,
    writes: data.metrics.measured_writes?.values.count || 0,
    errors: { count: data.metrics.measured_errors?.values.passes || 0, successful: data.metrics.measured_errors?.values.fails || 0, rate: data.metrics.measured_errors?.values.rate || 0 },
    groups: Object.fromEntries(Object.keys(latency).filter(name => data.metrics['measured_' + name]).map(name => [name, data.metrics['measured_' + name].values])),
    wall: data.metrics.measured_wall_ms?.values, metrics: data.metrics,
  }
  return { [__ENV.RESULT_PATH || '/results/result.json']: JSON.stringify(summary, null, 2), stdout: JSON.stringify({ ...summary, metrics: undefined }) + '\n' }
}
