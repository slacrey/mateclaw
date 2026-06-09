import { Bench, type Task } from 'tinybench'
import { PassThrough, Writable } from 'node:stream'
import { readFrame, writeFrame, writeJsonFrame } from '../src/internal/nm/server.js'

const bench = new Bench({ time: 1500, iterations: 100 })

function encodeFrame(payload: Buffer): Buffer {
  const header = Buffer.alloc(4)
  header.writeUInt32LE(payload.length, 0)
  return Buffer.concat([header, payload])
}

function loadedStream(payload: Buffer): PassThrough {
  const stream = new PassThrough()
  stream.end(encodeFrame(payload))
  return stream
}

class NullSink extends Writable {
  _write(_chunk: Buffer, _encoding: BufferEncoding, callback: (error?: Error | null) => void): void {
    callback()
  }
}

const payloads = {
  small: Buffer.from('hello'),
  medium: Buffer.from('a'.repeat(1024)),
  large: Buffer.from('a'.repeat(102_400)),
  huge: Buffer.from('a'.repeat(1_000_000)),
}

const jsonPayloads = {
  small: { echo: 'x' },
  medium: { tree: 'a'.repeat(1024) },
  large: { tree: 'a'.repeat(102_400) },
  huge: { tree: 'a'.repeat(1_000_000) },
}

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
  .add('readFrame(small)', async () => {
    await readFrame(loadedStream(payloads.small))
  })
  .add('readFrame(medium)', async () => {
    await readFrame(loadedStream(payloads.medium))
  })
  .add('readFrame(large)', async () => {
    await readFrame(loadedStream(payloads.large))
  })
  .add('readFrame(huge)', async () => {
    await readFrame(loadedStream(payloads.huge))
  })
  .add('writeFrame(small)', async () => {
    await writeFrame(new NullSink(), payloads.small)
  })
  .add('writeFrame(medium)', async () => {
    await writeFrame(new NullSink(), payloads.medium)
  })
  .add('writeFrame(large)', async () => {
    await writeFrame(new NullSink(), payloads.large)
  })
  .add('writeFrame(huge)', async () => {
    await writeFrame(new NullSink(), payloads.huge)
  })
  .add('writeJsonFrame(small)', async () => {
    await writeJsonFrame(new NullSink(), jsonPayloads.small)
  })
  .add('writeJsonFrame(medium)', async () => {
    await writeJsonFrame(new NullSink(), jsonPayloads.medium)
  })
  .add('writeJsonFrame(large)', async () => {
    await writeJsonFrame(new NullSink(), jsonPayloads.large)
  })
  .add('writeJsonFrame(huge)', async () => {
    await writeJsonFrame(new NullSink(), jsonPayloads.huge)
  })

await bench.run()
console.table(bench.table(tableRow))
