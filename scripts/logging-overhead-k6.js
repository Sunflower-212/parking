// Only run against an isolated fixture. Audit persistence remains enabled in both modes.
import http from 'k6/http'
import exec from 'k6/execution'
import { Counter, Rate, Trend } from 'k6/metrics'
const input = JSON.parse(open(__ENV.FIXTURE || '/work/logging-load-fixture.json'))
const calls = new Counter('measured_calls'), errors = new Rate('measured_errors')
const reads = new Counter('measured_reads'), writes = new Counter('measured_writes')
const read = new Trend('measured_read', true), write = new Trend('measured_write', true)
export const options = {
  scenarios: { sample: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', preAllocatedVUs: 10, maxVUs: 10, duration: '40s', gracefulStop: '10s' } },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { measured_errors: ['rate<0.001'] },
}
export default function () {
  const mutation = __ITER % 5 === 4
  const headers = { Authorization: input.token, 'Content-Type': 'application/json' }
  const response = mutation
    ? http.put('http://backend:8080/rule/update', JSON.stringify(input.rule), { headers })
    : http.get('http://backend:8080/rule/select', { headers })
  if (exec.instance.currentTestRunDuration >= 10000) {
    let ok = false
    try { ok = response.status === 200 && response.json('code') === 0 } catch { /* Count malformed output. */ }
    calls.add(1); (mutation ? writes : reads).add(1); errors.add(!ok); (mutation ? write : read).add(response.timings.duration)
  }
}
export function handleSummary(data) { return { [__ENV.RESULT || '/work/logging-overhead.json']: JSON.stringify(data, null, 2) } }
