import { Kind as EdgeMessageKind, make, parse } from '../src/internal/edgeproto/edgeproto.js'

function profile(label: string, iterations: number, body: () => void): void {
  if (global.gc) global.gc()
  const before = process.memoryUsage()
  const t0 = process.hrtime.bigint()

  for (let i = 0; i < iterations; i++) {
    body()
  }

  const t1 = process.hrtime.bigint()
  if (global.gc) global.gc()
  const after = process.memoryUsage()

  const elapsedMs = Number(t1 - t0) / 1e6
  const heapDeltaKb = (after.heapUsed - before.heapUsed) / 1024

  console.log(
    `${label.padEnd(40)} iter=${iterations}  time=${elapsedMs | 0}ms  heap_delta_kb=${heapDeltaKb | 0}`,
  )
}

if (!global.gc) {
  console.warn('global.gc is unavailable; run with: node --expose-gc --import tsx bench/heap-profile.ts')
}

const small = { kind: EdgeMessageKind.Ping, payload: { echo: 'hi' } }
const medium = { kind: EdgeMessageKind.Ping, payload: { tree: 'a'.repeat(1024) } }
const large = { kind: EdgeMessageKind.Ping, payload: { tree: 'a'.repeat(102_400) } }

profile('make() small', 100_000, () => {
  make(small)
})
profile('make() medium', 10_000, () => {
  make(medium)
})
profile('make() large', 1_000, () => {
  make(large)
})

const jsonSmall = JSON.stringify(make(small))
const jsonMedium = JSON.stringify(make(medium))
const jsonLarge = JSON.stringify(make(large))

profile('parse() small', 100_000, () => {
  parse(jsonSmall)
})
profile('parse() medium', 10_000, () => {
  parse(jsonMedium)
})
profile('parse() large', 1_000, () => {
  parse(jsonLarge)
})
