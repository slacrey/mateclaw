<template>
  <div class="root">
    <h1>MateClaw Browser Agent</h1>
    <button data-test="ping" @click="ping">Send ping</button>
    <ul class="log">
      <li v-for="(entry, i) in log" :key="i">
        <strong>{{ entry.kind }}</strong> {{ entry.summary }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'
import type { EdgeMessage } from '../shared/edge-protocol'

interface LogEntry {
  kind: string
  summary: string
}

const log = ref<LogEntry[]>([])

function append(e: LogEntry) {
  log.value = [...log.value, e].slice(-100)
}

async function ping() {
  const msg = makeEdgeMessage({
    kind: EdgeMessageKind.Ping,
    payload: { echo: `manual-${Date.now()}` },
  })
  await chrome.runtime.sendMessage({ kind: 'edge.outbound', message: msg })
  append({ kind: 'ping', summary: `echo=${String(msg.payload?.['echo'] ?? '')}` })
}

onMounted(() => {
  chrome.runtime.onMessage.addListener((req: unknown) => {
    const r = req as { kind?: string; message?: EdgeMessage }
    if (r?.kind !== 'edge.inbound' || !r.message) return
    const m = r.message
    append({ kind: m.kind, summary: JSON.stringify(m.payload ?? {}) })
  })
})
</script>

<style scoped>
.root {
  font: 14px/1.4 system-ui;
  padding: 12px;
}
.log {
  list-style: none;
  padding: 0;
}
.log li {
  padding: 4px 0;
  border-bottom: 1px solid #eee;
}
</style>
