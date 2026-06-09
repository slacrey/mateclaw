# Codex Task 06 — TypeScript perf baseline benchmarks

> 你（Codex）要为 `mateclaw-browser-bridge`（Node Native Host）写一组 micro
> benchmarks，给 Phase 1 协议关键热路径定**性能基线**。Phase 2 加上动作流
> 量后能用这条基线来判断"有没有变慢"。落地在
> `mateclaw-browser-bridge/bench/` 子目录。

## 项目背景

`mateclaw-browser-bridge` 是 TypeScript/Node 的 Native Host，跑在用户本机。
关键热路径有两条：

1. **EdgeMessage 信封编解码**：`make()` / `parse()` 在
   `src/internal/edgeproto/edgeproto.ts`。bridge 每条消息都过这两个函数。
2. **Native Messaging 帧编解码**：`readFrame()` / `writeFrame()` /
   `writeJsonFrame()` 在 `src/internal/nm/server.ts`。stdio 双向流的每帧都过。

我们希望知道：
- 单帧解码 P50 / P99 多少 ns？
- 编码呢？
- writeJsonFrame（JSON 序列化 + 帧封装）和 raw writeFrame 差多少？
- 不同 payload size（100B / 1KB / 100KB / 1MB）的曲线？
- 内存 GC 行为：每次解码分配多少 Buffer，需要不需要 pool？

## 你要交付的 4 个文件

### 1. `mateclaw-browser-bridge/bench/edge-protocol.bench.ts`

用 **tinybench**（轻量、`vitest` 友好）。npm 包：
```bash
pnpm add -D tinybench
```

测试场景：

```typescript
import { Bench } from 'tinybench';
import { make, parse, EdgeMessageKind } from '../src/internal/edgeproto/edgeproto.js';

const bench = new Bench({ time: 1500, iterations: 100 });

const payloadSmall  = { echo: 'hi' };
const payloadMedium = { tree: 'a'.repeat(1024) };
const payloadLarge  = { tree: 'a'.repeat(102_400) };
const payloadHuge   = { tree: 'a'.repeat(1_000_000) };

const msgSmall  = make({ kind: EdgeMessageKind.Ping, payload: payloadSmall });
const msgMedium = make({ kind: EdgeMessageKind.Ping, payload: payloadMedium });
// ... etc

const jsonSmall  = JSON.stringify(msgSmall);
const jsonMedium = JSON.stringify(msgMedium);
// ...

bench
  .add('make() small (15B payload)',      () => { make({ kind: EdgeMessageKind.Ping, payload: payloadSmall }); })
  .add('make() medium (1KB payload)',     () => { make({ kind: EdgeMessageKind.Ping, payload: payloadMedium }); })
  .add('make() large (100KB payload)',    () => { make({ kind: EdgeMessageKind.Ping, payload: payloadLarge }); })
  .add('JSON.stringify(small)',           () => { JSON.stringify(msgSmall); })
  .add('JSON.stringify(medium)',          () => { JSON.stringify(msgMedium); })
  .add('JSON.stringify(large)',           () => { JSON.stringify(msgLarge); })
  .add('parse(small)',                    () => { parse(jsonSmall); })
  .add('parse(medium)',                   () => { parse(jsonMedium); })
  .add('parse(large)',                    () => { parse(jsonLarge); });

await bench.run();
console.table(bench.table());
```

每场景跑至少 1.5s 或 100 次（tinybench 默认）。

### 2. `mateclaw-browser-bridge/bench/nm-codec.bench.ts`

```typescript
import { Bench } from 'tinybench';
import { Readable, Writable, PassThrough } from 'node:stream';
import { readFrame, writeFrame, writeJsonFrame } from '../src/internal/nm/server.js';

const bench = new Bench({ time: 1500 });

// helpers to pre-build pre-loaded streams and sinks
function loadedStream(payload: Buffer): Readable {
    const header = Buffer.alloc(4);
    header.writeUInt32LE(payload.length, 0);
    return Readable.from(Buffer.concat([header, payload]));
}

class NullSink extends Writable {
    _write(chunk: any, enc: any, cb: any) { cb(); }
}

const payloads = {
    small:  Buffer.from('hello'),
    medium: Buffer.from('a'.repeat(1024)),
    large:  Buffer.from('a'.repeat(102_400)),
    huge:   Buffer.from('a'.repeat(1_000_000)),
};

bench
  .add('readFrame(small)',  async () => { await readFrame(loadedStream(payloads.small)); })
  .add('readFrame(medium)', async () => { await readFrame(loadedStream(payloads.medium)); })
  .add('readFrame(large)',  async () => { await readFrame(loadedStream(payloads.large)); })
  .add('readFrame(huge)',   async () => { await readFrame(loadedStream(payloads.huge)); })
  .add('writeFrame(small)',  async () => { await writeFrame(new NullSink(), payloads.small); })
  .add('writeFrame(medium)', async () => { await writeFrame(new NullSink(), payloads.medium); })
  .add('writeFrame(huge)',   async () => { await writeFrame(new NullSink(), payloads.huge); })
  .add('writeJsonFrame(small)',  async () => { await writeJsonFrame(new NullSink(), { echo: 'x' }); })
  .add('writeJsonFrame(medium)', async () => { await writeJsonFrame(new NullSink(), { tree: 'a'.repeat(1024) }); });

await bench.run();
console.table(bench.table());
```

### 3. `mateclaw-browser-bridge/bench/heap-profile.ts`

简单的内存分配 profile，跑 1 万次 small/medium/large 解码，使用 `process.memoryUsage()` 抓 before/after `heapUsed`，打印 delta：

```typescript
import { make, parse, EdgeMessageKind } from '../src/internal/edgeproto/edgeproto.js';

function profile(label: string, iterations: number, body: () => void) {
    if (global.gc) global.gc();
    const before = process.memoryUsage();
    const t0 = process.hrtime.bigint();
    for (let i = 0; i < iterations; i++) body();
    const t1 = process.hrtime.bigint();
    if (global.gc) global.gc();
    const after = process.memoryUsage();
    console.log(`${label.padEnd(40)} iter=${iterations}  time=${Number(t1 - t0) / 1e6 | 0}ms  heap_delta_kb=${((after.heapUsed - before.heapUsed) / 1024) | 0}`);
}

const small = { kind: EdgeMessageKind.Ping, payload: { echo: 'hi' } };
const medium = { kind: EdgeMessageKind.Ping, payload: { tree: 'a'.repeat(1024) } };
const large = { kind: EdgeMessageKind.Ping, payload: { tree: 'a'.repeat(102_400) } };

profile('make() small',                  100_000, () => { make(small); });
profile('make() medium',                  10_000, () => { make(medium); });
profile('make() large',                    1_000, () => { make(large); });

const jsonSmall  = JSON.stringify(make(small));
const jsonMedium = JSON.stringify(make(medium));
const jsonLarge  = JSON.stringify(make(large));

profile('parse() small',                 100_000, () => { parse(jsonSmall); });
profile('parse() medium',                 10_000, () => { parse(jsonMedium); });
profile('parse() large',                   1_000, () => { parse(jsonLarge); });
```

跑法：`node --expose-gc bench/heap-profile.ts`（需要 `--expose-gc` 显式 GC，否则 `global.gc` 是 undefined，profile 函数应 graceful skip GC）。

### 4. `mateclaw-browser-bridge/bench/README.md`

3 个 section：

#### Running

```bash
cd mateclaw-browser-bridge
pnpm install
pnpm tsx bench/edge-protocol.bench.ts
pnpm tsx bench/nm-codec.bench.ts
node --expose-gc --import tsx bench/heap-profile.ts
```

#### Baseline numbers (will be filled in by maintainer after first run)

Empty 表格 with TODO:
```markdown
| Op                                   | Hz       | Mean (ns) | P99 (ns)  | Margin |
|--------------------------------------|----------|-----------|-----------|--------|
| make() small (15B payload)           | TODO     | TODO      | TODO      | TODO   |
| make() medium (1KB payload)          | TODO     | TODO      | TODO      | TODO   |
| ...                                  |          |           |           |        |
```

#### Performance budget (declared upfront, enforced manually post-Phase 2)

```markdown
- `make()` / `parse()` for typical (≤ 1KB) payloads should be < 5μs each — anything slower is a hot-path regression.
- `readFrame()` / `writeFrame()` for typical payloads should be < 50μs.
- Heap delta for 100k `make()` of small payloads should be < 5 MB after GC (proves no leak).

Phase 2 will add `action.execute` flow at ~1 msg/sec — these baselines have **100× headroom**. If we ever close to the budget, time to add Buffer pooling / pre-allocated envelope objects.
```

## 工程约束

- TypeScript strict mode（继承现有 tsconfig.json）
- 不要碰 `src/` 下任何文件 —— 只读，bench 单独跑
- `pnpm add -D tinybench tsx` 是允许的；不要引入更重的 benchmark 框架
  （don't use Benchmark.js — 它的 noise reduction 比 tinybench 弱，而且
  unmaintained）
- ESM 模块（`import ... from '../src/...js'` 注意路径要带 `.js` 扩展名，因为
  tsconfig moduleResolution 是 Bundler）
- 三个 bench 文件 + 一个 README

## 验收

- [ ] 4 文件齐全
- [ ] 安装 tinybench + tsx 后 `pnpm tsx bench/edge-protocol.bench.ts` 能跑出表格
- [ ] `node --expose-gc --import tsx bench/heap-profile.ts` 跑出 heap delta
  数字
- [ ] README 性能预算明确（5μs / 50μs / 5MB）
- [ ] 不修改 `src/` 下任何代码

## 交付格式

```
========== FILE: mateclaw-browser-bridge/bench/edge-protocol.bench.ts ==========
<内容>
========== FILE: mateclaw-browser-bridge/bench/nm-codec.bench.ts ==========
<内容>
========== FILE: mateclaw-browser-bridge/bench/heap-profile.ts ==========
<内容>
========== FILE: mateclaw-browser-bridge/bench/README.md ==========
<内容>
========== FILE: mateclaw-browser-bridge/package.json ==========
<只显示新增的 devDeps + bench scripts 部分的 diff>
========== TEST RUN ==========
<pnpm tsx bench/edge-protocol.bench.ts 的部分输出，证明能跑>
========== COMMIT MSG ==========
perf(browser-bridge): add Phase 1 benchmark baseline + perf budget

Three micro-benchmarks plus a heap-profile script under bench/:
- edge-protocol.bench.ts — make() / parse() / JSON.stringify across
  small/medium/large/huge payloads via tinybench
- nm-codec.bench.ts — readFrame / writeFrame / writeJsonFrame, same size
  ladder, against pre-loaded Readable + NullSink
- heap-profile.ts — 10k–100k iterations under --expose-gc to detect
  allocation leaks

README declares the Phase-1 perf budget (make/parse < 5μs typical,
readFrame/writeFrame < 50μs typical, no leak under 100k iterations)
which Phase 2's action stream will be measured against.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```
