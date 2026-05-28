# Browser Bridge Benchmarks

## Running

```bash
cd mateclaw-browser-bridge
pnpm install
pnpm tsx bench/edge-protocol.bench.ts
pnpm tsx bench/nm-codec.bench.ts
node --expose-gc --import tsx bench/heap-profile.ts
```

Equivalent package scripts:

```bash
pnpm bench:edge
pnpm bench:nm
pnpm bench:heap
pnpm bench
```

## Baseline numbers (will be filled in by maintainer after first run)

| Op                                   | Hz   | Mean (ns) | P99 (ns) | Margin |
|--------------------------------------|------|-----------|----------|--------|
| make() small (15B payload)           | TODO | TODO      | TODO     | TODO   |
| make() medium (1KB payload)          | TODO | TODO      | TODO     | TODO   |
| make() large (100KB payload)         | TODO | TODO      | TODO     | TODO   |
| make() huge (1MB payload)            | TODO | TODO      | TODO     | TODO   |
| JSON.stringify(small)                | TODO | TODO      | TODO     | TODO   |
| JSON.stringify(medium)               | TODO | TODO      | TODO     | TODO   |
| JSON.stringify(large)                | TODO | TODO      | TODO     | TODO   |
| JSON.stringify(huge)                 | TODO | TODO      | TODO     | TODO   |
| parse(small)                         | TODO | TODO      | TODO     | TODO   |
| parse(medium)                        | TODO | TODO      | TODO     | TODO   |
| parse(large)                         | TODO | TODO      | TODO     | TODO   |
| parse(huge)                          | TODO | TODO      | TODO     | TODO   |
| readFrame(small)                     | TODO | TODO      | TODO     | TODO   |
| readFrame(medium)                    | TODO | TODO      | TODO     | TODO   |
| readFrame(large)                     | TODO | TODO      | TODO     | TODO   |
| readFrame(huge)                      | TODO | TODO      | TODO     | TODO   |
| writeFrame(small)                    | TODO | TODO      | TODO     | TODO   |
| writeFrame(medium)                   | TODO | TODO      | TODO     | TODO   |
| writeFrame(large)                    | TODO | TODO      | TODO     | TODO   |
| writeFrame(huge)                     | TODO | TODO      | TODO     | TODO   |
| writeJsonFrame(small)                | TODO | TODO      | TODO     | TODO   |
| writeJsonFrame(medium)               | TODO | TODO      | TODO     | TODO   |
| writeJsonFrame(large)                | TODO | TODO      | TODO     | TODO   |
| writeJsonFrame(huge)                 | TODO | TODO      | TODO     | TODO   |

## Performance budget (declared upfront, enforced manually post-Phase 2)

- `make()` / `parse()` for typical (≤ 1KB) payloads should be < 5μs each; anything slower is a hot-path regression.
- `readFrame()` / `writeFrame()` for typical payloads should be < 50μs.
- Heap delta for 100k `make()` of small payloads should be < 5 MB after GC (proves no leak).

Phase 2 will add `action.execute` flow at ~1 msg/sec — these baselines have **100× headroom**. If we ever close to the budget, time to add Buffer pooling / pre-allocated envelope objects.
