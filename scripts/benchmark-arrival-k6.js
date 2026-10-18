// Reuse the exact mixed-request implementation, with held arrival-rate steps.
import * as workload from './benchmark-k6.js'
const seconds = Number(__ENV.SECONDS || 300)
const warmup = Number(__ENV.WARMUP || 120)
const segment = seconds / 3
export const options = {
  ...workload.options,
  scenarios: {
    load: {
      ...workload.options.scenarios.load,
      stages: [
        { duration: `${warmup}s`, target: 100 },
        ...[200, 600, 1200].flatMap(target => [
          { duration: '1s', target },
          { duration: `${segment - 1}s`, target },
        ]),
      ],
    },
  },
}
export const setup = workload.setup
export const teardown = workload.teardown
export const handleSummary = workload.handleSummary
export default function(data) { return workload.default(data) }
