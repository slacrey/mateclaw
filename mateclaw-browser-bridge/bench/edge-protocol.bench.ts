import { Bench, type Task } from 'tinybench'
import { Kind as EdgeMessageKind, make, parse } from '../src/internal/edgeproto/edgeproto.js'

const bench = new Bench({ time: 1500, iterations: 100 })

const payloadSmall = { echo: 'hi' }
const payloadMedium = { tree: 'a'.repeat(1024) }
const payloadLarge = { tree: 'a'.repeat(102_400) }
const payloadHuge = { tree: 'a'.repeat(1_000_000) }

const msgSmall = make({ kind: EdgeMessageKind.Ping, payload: payloadSmall })
const msgMedium = make({ kind: EdgeMessageKind.Ping, payload: payloadMedium })
const msgLarge = make({ kind: EdgeMessageKind.Ping, payload: payloadLarge })
const msgHuge = make({ kind: EdgeMessageKind.Ping, payload: payloadHuge })

const jsonSmall = JSON.stringify(msgSmall)
const jsonMedium = JSON.stringify(msgMedium)
const jsonLarge = JSON.stringify(msgLarge)
const jsonHuge = JSON.stringify(msgHuge)

function ns(ms: number): number {
  return Math.round(ms * 1_000_000)
}

function tableRow(task: Task): Record<string, number | string> {
  const { result } = task

  if (result.state === 'completed' || result.state === 'aborted-with-statistics') {
    return {
      Op: task.name,
      Hz: Math.round(result.throughput.mean),
      'Mean (ns)': ns(result.latency.mean),
      'P50 (ns)': ns(result.latency.p50),
      'P99 (ns)': ns(result.latency.p99),
      Margin: `+/- ${result.latency.rme.toFixed(2)}%`,
    }
  }

  if (result.state === 'errored') {
    return {
      Op: task.name,
      Error: result.error.message,
    }
  }

  return {
    Op: task.name,
    State: result.state,
  }
}

bench
  .add('make() small (15B payload)', () => {
    make({ kind: EdgeMessageKind.Ping, payload: payloadSmall })
  })
  .add('make() medium (1KB payload)', () => {
    make({ kind: EdgeMessageKind.Ping, payload: payloadMedium })
  })
  .add('make() large (100KB payload)', () => {
    make({ kind: EdgeMessageKind.Ping, payload: payloadLarge })
  })
  .add('make() huge (1MB payload)', () => {
    make({ kind: EdgeMessageKind.Ping, payload: payloadHuge })
  })
  .add('JSON.stringify(small)', () => {
    JSON.stringify(msgSmall)
  })
  .add('JSON.stringify(medium)', () => {
    JSON.stringify(msgMedium)
  })
  .add('JSON.stringify(large)', () => {
    JSON.stringify(msgLarge)
  })
  .add('JSON.stringify(huge)', () => {
    JSON.stringify(msgHuge)
  })
  .add('parse(small)', () => {
    parse(jsonSmall)
  })
  .add('parse(medium)', () => {
    parse(jsonMedium)
  })
  .add('parse(large)', () => {
    parse(jsonLarge)
  })
  .add('parse(huge)', () => {
    parse(jsonHuge)
  })

await bench.run()
console.table(bench.table(tableRow))
