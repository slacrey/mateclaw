<template>
  <section class="lead-live-panel" :class="{ 'is-terminal': terminal }">
    <header class="live-header">
      <div>
        <span class="live-kicker">实时执行</span>
        <h2>{{ terminal ? '获客任务已结束' : '获客任务执行中' }}</h2>
        <p>
          Run {{ runId || '-' }}
          <span v-if="taskId"> · Task {{ taskId }}</span>
          <span> · {{ statusLabel(displayRun?.status) }}</span>
        </p>
      </div>
      <div class="live-actions">
        <span class="connection-chip" :class="connectionTone">
          {{ connectionText }}
        </span>
        <button
          class="ghost-button"
          type="button"
          :disabled="refreshing"
          @click="refreshSnapshot"
        >
          刷新
        </button>
        <button
          class="danger-button"
          type="button"
          :disabled="terminal || cancelling"
          @click="cancelRun"
        >
          {{ cancelling ? '停止中' : '停止任务' }}
        </button>
      </div>
    </header>

    <div v-if="fallbackActive" class="fallback-banner">
      实时连接恢复中，已切换为 2 秒刷新一次。
    </div>

    <div class="live-layout">
      <section class="timeline-panel">
        <div class="section-head">
          <div>
            <h3>执行进展</h3>
            <p>{{ timelineItems.length }} 条进展</p>
          </div>
        </div>

        <ol v-if="timelineItems.length" class="timeline-list">
          <li
            v-for="item in timelineItems"
            :key="item.key"
            class="timeline-item"
            :class="`tone-${item.tone}`"
          >
            <span class="timeline-node" aria-hidden="true"></span>
            <div class="timeline-body">
              <div class="timeline-line">
                <strong>{{ item.title }}</strong>
                <span>{{ item.time }}</span>
              </div>
              <p v-if="item.summary">{{ item.summary }}</p>
              <dl v-if="item.fields.length" class="timeline-fields">
                <template v-for="field in item.fields" :key="field.key">
                  <dt>{{ field.label }}</dt>
                  <dd>{{ field.value }}</dd>
                </template>
              </dl>
            </div>
          </li>
        </ol>
        <div v-else class="empty-block">任务刚启动，正在等待第一条进展。</div>
      </section>

      <aside class="status-panel">
        <section>
          <h3>实时统计</h3>
          <div class="metric-grid">
            <div v-for="metric in metrics" :key="metric.label" class="metric-item">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
            </div>
          </div>
        </section>

        <section>
          <h3>当前视频</h3>
          <div class="current-video">
            <strong>{{ currentVideoTitle }}</strong>
            <p>{{ currentVideoMeta }}</p>
          </div>
        </section>

        <section>
          <h3>触达状态</h3>
          <div class="engagement-list">
            <div v-for="engagement in engagementRows" :key="engagement.id" class="engagement-row">
              <span>{{ engagement.sent ? '已发送' : statusLabel(engagement.status) }}</span>
              <strong>{{ engagement.draftText || engagement.failureMessage || '触达记录' }}</strong>
            </div>
            <div v-if="!engagementRows.length" class="empty-mini">暂无触达记录</div>
          </div>
        </section>
      </aside>
    </div>

    <section v-if="terminal" class="final-summary">
      <div class="section-head">
        <div>
          <h3>最终汇总</h3>
          <p>{{ finalSummaryText }}</p>
        </div>
      </div>

      <div class="final-table-wrap">
        <table>
          <thead>
            <tr>
              <th>视频</th>
              <th>状态</th>
              <th>评论</th>
              <th>匹配</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="video in videoRows" :key="String(video.videoKey ?? video.index ?? video.title)">
              <td>{{ videoNumber(video) }}</td>
              <td>{{ statusLabel(video.status) }}</td>
              <td>{{ countLabel(video.commentsCollected) }} / {{ countLabel(video.declaredCommentCount) }}</td>
              <td>{{ countLabel(video.matchedComments) }}</td>
              <td>{{ video.stopReason || video.failureCode || video.errorCode || '-' }}</td>
            </tr>
            <tr v-if="!videoRows.length">
              <td colspan="5">暂无视频明细</td>
            </tr>
          </tbody>
        </table>
      </div>

      <details class="technical-detail">
        <summary>技术明细</summary>
        <DouyinLeadRunResult
          :run="displayRun"
          :comments="displayRun?.comments ?? []"
          :engagements="displayRun?.engagements ?? []"
          :profiles="[]"
          :loading="refreshing"
        />
      </details>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { leadAcquisitionApi } from '@/api'
import type {
  DouyinLeadAcquisitionRunResponse,
  DouyinLeadEngagement,
  DouyinLeadRunSummary,
  DouyinLeadRunVideoResult,
  DouyinLeadTimelineEvent,
} from '@/api'
import { mcToast } from '@/composables/useMcToast'
import DouyinLeadRunResult from '@/components/lead/DouyinLeadRunResult.vue'

type JsonRecord = Record<string, unknown>

const props = defineProps<{
  runId: string | number | null
  taskId?: string | number | null
  initialRun?: DouyinLeadAcquisitionRunResponse | null
}>()

const emit = defineEmits<{
  (event: 'update:run', run: DouyinLeadAcquisitionRunResponse): void
  (event: 'terminal', run: DouyinLeadAcquisitionRunResponse): void
}>()

const sseEventNames = [
  'run_created',
  'run_started',
  'run_status_changed',
  'lead.search.started',
  'lead.search.completed',
  'lead.sort.started',
  'lead.sort.completed',
  'lead.video.started',
  'lead.video.opened',
  'lead.comments.opened',
  'lead.comments.region_detected',
  'lead.comments.collecting',
  'lead.comments.collected',
  'lead.comment.matched',
  'lead.comment.match_skipped',
  'lead.engagement.started',
  'lead.engagement.completed',
  'lead.engagement.skipped',
  'lead.video.completed',
  'lead.video.failed',
  'lead.run.summary',
  'lead.run.failed',
  'run_snapshot',
  'heartbeat',
  'done',
] as const

const liveRun = ref<DouyinLeadAcquisitionRunResponse | null>(normalizeRun(props.initialRun))
const connected = ref(false)
const fallbackActive = ref(false)
const refreshing = ref(false)
const cancelling = ref(false)
const lastEventId = ref<string | null>(null)

let source: EventSource | null = null
let pollTimer: ReturnType<typeof window.setInterval> | null = null
let reconnectTimer: ReturnType<typeof window.setTimeout> | null = null

const displayRun = computed(() => normalizeRun(liveRun.value))
const events = computed(() => displayRun.value?.events ?? [])
const terminal = computed(() => isTerminalStatus(displayRun.value?.status))
const taskId = computed(() => props.taskId ?? displayRun.value?.taskId ?? null)

const summaryPayload = computed<JsonRecord>(() => {
  const event = [...events.value].reverse().find(item => item.type === 'lead.run.summary')
  const payload = parsePayload(event?.payloadJson)
  const nested = recordValue(payload.summary)
  return nested ? { ...payload, ...nested } : payload
})

const structuredSummary = computed<Partial<DouyinLeadRunSummary>>(() => {
  return displayRun.value?.summary ?? displayRun.value?.runSummary ?? {}
})

const videoRows = computed<DouyinLeadRunVideoResult[]>(() => {
  const fromStructured = structuredSummary.value.videoResults
  if (Array.isArray(fromStructured)) return fromStructured
  const fromPayload = summaryPayload.value.videoResults
  if (Array.isArray(fromPayload)) return fromPayload as DouyinLeadRunVideoResult[]
  return []
})

const engagementRows = computed<DouyinLeadEngagement[]>(() => displayRun.value?.engagements ?? [])

const runMetrics = computed(() => ({
  requestedVideos: firstNumber(
    displayRun.value?.requestedVideoLimit,
    structuredSummary.value.requestedVideoLimit,
    summaryPayload.value.requestedVideoLimit,
    summaryPayload.value.videoLimit,
  ),
  processedVideos: firstNumber(
    displayRun.value?.processedVideos,
    structuredSummary.value.processedVideos,
    summaryPayload.value.processedVideos,
    videoRows.value.length,
  ),
  commentsCollected: firstNumber(
    displayRun.value?.commentsCollected,
    structuredSummary.value.commentsCollected,
    summaryPayload.value.commentsCollected,
    displayRun.value?.comments?.length,
  ),
  matchedComments: firstNumber(
    displayRun.value?.matchedComments,
    structuredSummary.value.matchedComments,
    summaryPayload.value.matchedComments,
    displayRun.value?.matches?.length,
  ),
  engagementsCreated: firstNumber(
    displayRun.value?.engagementsCreated,
    structuredSummary.value.engagementsCreated,
    summaryPayload.value.engagementsCreated,
    engagementRows.value.length,
  ),
  failedVideos: firstNumber(
    displayRun.value?.failedVideos,
    structuredSummary.value.failedVideos,
    summaryPayload.value.failedVideos,
  ),
}))

const metrics = computed(() => [
  { label: '视频进度', value: `${countLabel(runMetrics.value.processedVideos)} / ${countLabel(runMetrics.value.requestedVideos)}` },
  { label: '评论', value: countLabel(runMetrics.value.commentsCollected) },
  { label: '匹配', value: countLabel(runMetrics.value.matchedComments) },
  { label: '触达', value: countLabel(runMetrics.value.engagementsCreated) },
  { label: '失败视频', value: countLabel(runMetrics.value.failedVideos) },
])

const currentVideoPayload = computed<JsonRecord>(() => {
  const event = [...events.value].reverse().find(item => [
    'lead.video.started',
    'lead.video.opened',
    'lead.comments.collecting',
    'lead.comments.collected',
  ].includes(item.type))
  return parsePayload(event?.payloadJson)
})

const currentVideoTitle = computed(() => {
  const title = stringValue(currentVideoPayload.value.title)
  if (title) return title
  const latest = videoRows.value.at(-1)
  return latest?.title || (terminal.value ? '任务已结束' : '正在准备视频')
})

const currentVideoMeta = computed(() => {
  const index = displayVideoNumber(
    currentVideoPayload.value.videoNumber,
    currentVideoPayload.value.videoIndex,
    currentVideoPayload.value.index,
  )
  const comments = firstNumber(currentVideoPayload.value.commentsCollected, currentVideoPayload.value.commentsInWindow)
  const pieces = [
    index != null ? `第 ${index} 个视频` : '',
    comments != null ? `已采集 ${comments} 条评论` : '',
    statusLabel(stringValue(currentVideoPayload.value.status) || displayRun.value?.status),
  ].filter(Boolean)
  return pieces.join(' · ') || '等待视频打开'
})

const timelineItems = computed(() => {
  return events.value.map((event, index) => {
    const payload = parsePayload(event.payloadJson)
    return {
      key: event.id || `${event.type}-${index}`,
      title: businessTitle(event.type),
      tone: eventTone(event),
      time: formatTime(event.createTime, index),
      summary: eventSummary(event.type, payload),
      fields: eventFields(event.type, payload),
    }
  })
})

const connectionTone = computed(() => {
  if (terminal.value) return 'done'
  if (fallbackActive.value) return 'warn'
  if (connected.value) return 'live'
  return 'idle'
})

const connectionText = computed(() => {
  if (terminal.value) return '已结束'
  if (fallbackActive.value) return '实时连接恢复中'
  if (connected.value) return '实时连接中'
  return '连接中'
})

const finalSummaryText = computed(() => {
  return [
    `处理视频 ${countLabel(runMetrics.value.processedVideos)} / ${countLabel(runMetrics.value.requestedVideos)}`,
    `采集评论 ${countLabel(runMetrics.value.commentsCollected)} 条`,
    `匹配 ${countLabel(runMetrics.value.matchedComments)} 条`,
    `触达 ${countLabel(runMetrics.value.engagementsCreated)} 次`,
  ].join('，')
})

watch(() => props.initialRun, (run) => {
  mergeRun(run, false)
}, { deep: true })

watch(() => props.runId, () => {
  resetConnection()
  if (props.runId && !terminal.value) connectStream()
})

onMounted(() => {
  if (props.runId && !terminal.value) connectStream()
})

onUnmounted(() => {
  resetConnection()
})

function connectStream() {
  if (!props.runId) return
  stopStream()
  stopPolling()
  fallbackActive.value = false

  const url = leadAcquisitionApi.streamDouyinRunEventsUrl(props.runId, lastEventId.value)
  source = new EventSource(url)

  source.onopen = () => {
    connected.value = true
    fallbackActive.value = false
  }

  source.onerror = () => {
    connected.value = false
    stopStream()
    if (!terminal.value) {
      fallbackActive.value = true
      startPolling()
      scheduleReconnect()
    }
  }

  for (const name of sseEventNames) {
    source.addEventListener(name, (event) => handleStreamEvent(name, event as MessageEvent))
  }
}

function handleStreamEvent(name: string, event: MessageEvent) {
  if (event.lastEventId) lastEventId.value = event.lastEventId
  const data = parseData(event.data)
  const record = recordValue(data) ?? {}

  if (name === 'heartbeat') return
  if (name === 'run_snapshot') {
    mergeRun(isRunResponse(data) ? data : recordValue(record.run))
    return
  }
  if (name === 'done') {
    mergeRun(isRunResponse(data) ? data : recordValue(record.run))
    refreshSnapshot().finally(() => resetConnection())
    return
  }

  if (isRunResponse(data)) {
    mergeRun(data)
    return
  }

  addTimelineEvent(normalizeTimelineEvent(name, data, event.lastEventId))

  const snapshot = recordValue(record.run) ?? recordValue(record.snapshot)
  if (snapshot) mergeRun(snapshot)
}

function startPolling() {
  if (pollTimer || terminal.value) return
  refreshSnapshot()
  pollTimer = window.setInterval(() => {
    refreshSnapshot()
  }, 2_000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function scheduleReconnect() {
  if (reconnectTimer || terminal.value) return
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    if (!terminal.value) connectStream()
  }, 5_000)
}

function stopStream() {
  if (source) {
    source.close()
    source = null
  }
  connected.value = false
}

function resetConnection() {
  stopStream()
  stopPolling()
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  fallbackActive.value = false
}

async function refreshSnapshot() {
  if (!props.runId) return
  refreshing.value = true
  try {
    const response = await leadAcquisitionApi.getDouyinRun(props.runId)
    const run = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    mergeRun(run)
    if (run && isTerminalStatus(run.status)) resetConnection()
  } catch (error) {
    if (!fallbackActive.value && !terminal.value) fallbackActive.value = true
  } finally {
    refreshing.value = false
  }
}

async function cancelRun() {
  if (!props.runId || terminal.value) return
  cancelling.value = true
  try {
    await leadAcquisitionApi.cancelRun(props.runId)
    mcToast.success('已请求停止获客任务')
    await refreshSnapshot()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  } finally {
    cancelling.value = false
  }
}

function mergeRun(candidate: unknown, emitUpdate = true) {
  if (!candidate || typeof candidate !== 'object') return
  const next = normalizeRun(candidate as DouyinLeadAcquisitionRunResponse)
  if (!next) return
  const current = liveRun.value
  liveRun.value = {
    ...current,
    ...next,
    comments: next.comments?.length ? next.comments : (current?.comments ?? []),
    matches: next.matches?.length ? next.matches : (current?.matches ?? []),
    engagements: next.engagements?.length ? next.engagements : (current?.engagements ?? []),
    events: mergeEvents(current?.events ?? [], next.events ?? []),
  }
  updateLastEventId(liveRun.value.events)
  if (emitUpdate) notifyRun()
}

function addTimelineEvent(event: DouyinLeadTimelineEvent) {
  const current = normalizeRun(liveRun.value) ?? emptyRun()
  current.events = mergeEvents(current.events ?? [], [event])
  liveRun.value = current
  updateLastEventId(current.events)
  notifyRun()
}

function notifyRun() {
  const run = displayRun.value
  if (!run) return
  emit('update:run', run)
  if (isTerminalStatus(run.status)) emit('terminal', run)
}

function mergeEvents(existing: DouyinLeadTimelineEvent[], incoming: DouyinLeadTimelineEvent[]) {
  const map = new Map<string, DouyinLeadTimelineEvent>()
  const merged: DouyinLeadTimelineEvent[] = []
  for (const event of [...existing, ...incoming]) {
    const key = event.id || `${event.type}:${event.createTime ?? ''}:${event.payloadJson ?? ''}`
    if (map.has(key)) continue
    map.set(key, event)
    merged.push(event)
  }
  return merged.sort((a, b) => compareEvent(a, b))
}

function compareEvent(a: DouyinLeadTimelineEvent, b: DouyinLeadTimelineEvent): number {
  const aId = numeric(a.id)
  const bId = numeric(b.id)
  if (aId != null && bId != null) return aId - bId
  const aTime = Date.parse(a.createTime ?? '')
  const bTime = Date.parse(b.createTime ?? '')
  if (Number.isFinite(aTime) && Number.isFinite(bTime)) return aTime - bTime
  return 0
}

function updateLastEventId(items: DouyinLeadTimelineEvent[]) {
  for (const event of items) {
    const value = numeric(event.id)
    const current = numeric(lastEventId.value)
    if (value != null && (current == null || value > current)) lastEventId.value = String(event.id)
  }
}

function normalizeTimelineEvent(type: string, data: unknown, eventId?: string): DouyinLeadTimelineEvent {
  const record = recordValue(data) ?? {}
  const payloadJson = typeof record.payloadJson === 'string'
    ? record.payloadJson
    : JSON.stringify(record)
  return {
    id: stringValue(record.id) || eventId || `${type}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    stepId: stringValue(record.stepId) || null,
    type: (stringValue(record.type) || type) as DouyinLeadTimelineEvent['type'],
    severity: stringValue(record.severity) || severityFor(type),
    payloadJson,
    createTime: stringValue(record.createTime) || new Date().toISOString(),
  }
}

function normalizeRun(run: DouyinLeadAcquisitionRunResponse | null | undefined): DouyinLeadAcquisitionRunResponse | null {
  if (!run) return null
  return {
    ...run,
    comments: run.comments ?? [],
    matches: run.matches ?? [],
    engagements: run.engagements ?? [],
    events: run.events ?? [],
  }
}

function emptyRun(): DouyinLeadAcquisitionRunResponse {
  return {
    runId: props.runId == null ? null : String(props.runId),
    taskId: taskId.value == null ? null : String(taskId.value),
    status: 'running',
    commentsCollected: 0,
    matchedComments: 0,
    comments: [],
    matches: [],
    engagements: [],
    events: [],
  }
}

function isRunResponse(value: unknown): value is DouyinLeadAcquisitionRunResponse {
  return !!value && typeof value === 'object' && (
    'runId' in value ||
    'taskId' in value ||
    'commentsCollected' in value ||
    'events' in value
  )
}

function unwrapApiData<T>(response: unknown, fallback: T): T {
  if (!response || typeof response !== 'object') return fallback
  const candidate = response as { data?: unknown }
  if ('data' in candidate) {
    const data = candidate.data as { data?: unknown } | unknown
    if (data && typeof data === 'object' && 'data' in (data as Record<string, unknown>)) {
      return ((data as { data?: unknown }).data ?? fallback) as T
    }
    return (candidate.data ?? fallback) as T
  }
  return response as T
}

function parseData(raw: string): unknown {
  if (!raw) return {}
  try {
    return JSON.parse(raw)
  } catch {
    return { message: raw }
  }
}

function parsePayload(raw: string | null | undefined): JsonRecord {
  if (!raw) return {}
  const parsed = parseData(raw)
  return recordValue(parsed) ?? {}
}

function recordValue(value: unknown): JsonRecord | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonRecord : null
}

function stringValue(value: unknown): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}

function numeric(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return null
}

function firstNumber(...values: unknown[]): number | null {
  for (const value of values) {
    const next = numeric(value)
    if (next != null) return next
  }
  return null
}

function countLabel(value: unknown): string {
  const num = numeric(value)
  return num == null ? '-' : num.toLocaleString()
}

function isTerminalStatus(status?: string | null): boolean {
  return ['succeeded', 'success', 'completed', 'failed', 'aborted', 'cancelled', 'canceled'].includes(String(status || '').toLowerCase())
}

function statusLabel(status?: string | null): string {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'running') return '执行中'
  if (normalized === 'created') return '已创建'
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed') return '成功'
  if (normalized === 'failed') return '失败'
  if (normalized === 'aborted' || normalized === 'cancelled' || normalized === 'canceled') return '已停止'
  if (normalized === 'sent') return '已发送'
  return status || '-'
}

function severityFor(type: string): string {
  if (type.endsWith('.failed') || type === 'lead.run.failed') return 'error'
  return 'info'
}

function eventTone(event: DouyinLeadTimelineEvent): 'success' | 'danger' | 'warning' | 'running' | 'neutral' {
  const severity = String(event.severity || '').toLowerCase()
  if (severity === 'error' || event.type.endsWith('.failed') || event.type === 'lead.run.failed') return 'danger'
  if (severity === 'warn' || severity === 'warning') return 'warning'
  if (event.type.endsWith('.completed') || event.type === 'lead.run.summary') return 'success'
  if (event.type.endsWith('.started') || event.type === 'run_started' || event.type === 'lead.comments.collecting') return 'running'
  return 'neutral'
}

function businessTitle(type: string): string {
  const labels: Record<string, string> = {
    run_created: '任务已创建',
    run_started: '任务开始执行',
    run_status_changed: '任务状态更新',
    'lead.search.started': '开始搜索',
    'lead.search.completed': '搜索完成',
    'lead.sort.started': '开始应用排序',
    'lead.sort.completed': '排序已应用',
    'lead.video.started': '开始处理视频',
    'lead.video.opened': '视频已打开',
    'lead.comments.opened': '评论区已打开',
    'lead.comments.region_detected': '评论区已定位',
    'lead.comments.collecting': '正在采集评论',
    'lead.comments.collected': '评论采集完成',
    'lead.comment.matched': '命中匹配评论',
    'lead.comment.match_skipped': '跳过评论匹配',
    'lead.engagement.started': '开始触达线索',
    'lead.engagement.completed': '触达完成',
    'lead.engagement.skipped': '跳过触达',
    'lead.video.completed': '视频处理完成',
    'lead.video.failed': '视频处理失败',
    'lead.run.summary': '生成任务汇总',
    'lead.run.failed': '任务执行失败',
    run_snapshot: '任务快照',
    done: '任务结束',
  }
  return labels[type] ?? '任务进展'
}

function eventSummary(type: string, payload: JsonRecord): string {
  const message = stringValue(payload.message)
  if (message) return message
  if (type === 'lead.comments.collected') {
    const comments = firstNumber(payload.commentsCollected, payload.commentsInPage, payload.newComments)
    const declared = firstNumber(payload.declaredCommentCount)
    return `已采集 ${countLabel(comments)} 条评论${declared != null ? `，页面声明 ${countLabel(declared)} 条` : ''}`
  }
  if (type === 'lead.comment.matched') {
    const author = stringValue(payload.authorName) || stringValue(payload.author)
    const text = stringValue(payload.text) || stringValue(payload.commentText)
    return [author ? `作者：${author}` : '', text ? `评论：${clip(text, 80)}` : '发现一条匹配评论'].filter(Boolean).join('，')
  }
  if (type === 'lead.engagement.completed') {
    const sent = payload.sent === true ? '私信已发送' : '触达动作已完成'
    const author = stringValue(payload.authorName) || stringValue(payload.profileName)
    return author ? `${author}：${sent}` : sent
  }
  if (type === 'lead.video.failed' || type === 'lead.run.failed') {
    return stringValue(payload.failureCode) || stringValue(payload.errorCode) || stringValue(payload.reason) || '执行失败'
  }
  const title = stringValue(payload.title)
  if (title) return clip(title, 100)
  const status = stringValue(payload.status)
  if (status) return statusLabel(status)
  return ''
}

function eventFields(type: string, payload: JsonRecord): Array<{ key: string; label: string; value: string }> {
  const keys = type === 'lead.run.summary'
    ? ['requestedVideoLimit', 'processedVideos', 'succeededVideos', 'failedVideos', 'commentsCollected', 'matchedComments', 'engagementsCreated']
    : ['videoNumber', 'videoIndex', 'commentsCollected', 'declaredCommentCount', 'matchedComments', 'status', 'stopReason', 'failureCode']
  return keys
    .filter(key => payload[key] != null && payload[key] !== '')
    .slice(0, 5)
    .map(key => ({
      key,
      label: fieldLabel(key),
      value: typeof payload[key] === 'object' ? JSON.stringify(payload[key]) : String(payload[key]),
    }))
}

function fieldLabel(key: string): string {
  const labels: Record<string, string> = {
    requestedVideoLimit: '请求视频',
    processedVideos: '已处理视频',
    succeededVideos: '成功视频',
    failedVideos: '失败视频',
    commentsCollected: '评论数',
    declaredCommentCount: '声明评论',
    matchedComments: '匹配数',
    engagementsCreated: '触达数',
    videoNumber: '视频',
    videoIndex: '视频序号',
    status: '状态',
    stopReason: '停止原因',
    failureCode: '失败代码',
  }
  return labels[key] ?? key
}

function formatTime(value: string | null | undefined, index: number): string {
  if (!value) return `#${index + 1}`
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 19)
  return date.toLocaleTimeString()
}

function clip(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}...` : value
}

function displayVideoNumber(videoNumberValue: unknown, videoIndexValue: unknown, fallbackIndexValue?: unknown): number | null {
  const explicit = firstNumber(videoNumberValue)
  if (explicit != null) return explicit
  const index = firstNumber(videoIndexValue, fallbackIndexValue)
  return index == null ? null : index + 1
}

function videoNumber(video: DouyinLeadRunVideoResult): string {
  const value = displayVideoNumber(video.videoNumber, video.index)
  return value == null ? '-' : String(value)
}
</script>

<style scoped>
.lead-live-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
  padding: 18px;
}

.live-header,
.section-head,
.live-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.live-header h2,
.section-head h3,
.status-panel h3 {
  margin: 0;
  color: var(--mc-text-primary);
}

.live-header h2 {
  font-size: 18px;
}

.live-header p,
.section-head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.live-kicker {
  display: block;
  margin-bottom: 5px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.live-actions {
  align-items: center;
  flex-wrap: wrap;
}

.connection-chip {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.connection-chip.live {
  color: #15803d;
  background: rgba(22, 163, 74, .12);
}

.connection-chip.warn {
  color: #b45309;
  background: rgba(245, 158, 11, .14);
}

.connection-chip.done {
  color: var(--mc-text-primary);
}

.ghost-button,
.danger-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 0 12px;
  font-weight: 650;
  cursor: pointer;
}

.danger-button {
  border-color: color-mix(in srgb, var(--mc-danger, #dc2626) 32%, var(--mc-border));
  color: var(--mc-danger, #dc2626);
}

button:disabled {
  cursor: not-allowed;
  opacity: .58;
}

.fallback-banner {
  padding: 10px 12px;
  border: 1px solid rgba(245, 158, 11, .35);
  border-radius: 8px;
  background: rgba(245, 158, 11, .11);
  color: #92400e;
  font-size: 13px;
  font-weight: 650;
}

.live-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
}

.timeline-panel,
.status-panel,
.final-summary {
  min-width: 0;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 14px;
}

.status-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.status-panel h3 {
  margin-bottom: 10px;
  font-size: 14px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
  padding: 10px;
}

.metric-item span {
  display: block;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.metric-item strong {
  display: block;
  margin-top: 5px;
  color: var(--mc-text-primary);
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.current-video,
.engagement-row {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
  padding: 10px;
}

.current-video strong,
.engagement-row strong {
  display: block;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.45;
}

.current-video p {
  margin: 6px 0 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.engagement-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.engagement-row span {
  display: block;
  margin-bottom: 5px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.timeline-list {
  position: relative;
  list-style: none;
  margin: 14px 0 0;
  padding: 0;
}

.timeline-list::before {
  content: "";
  position: absolute;
  left: 8px;
  top: 8px;
  bottom: 8px;
  width: 1px;
  background: var(--mc-border);
}

.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 10px;
  padding-bottom: 12px;
}

.timeline-node {
  position: relative;
  z-index: 1;
  width: 17px;
  height: 17px;
  margin-top: 10px;
  border: 3px solid var(--mc-bg);
  border-radius: 50%;
  background: var(--mc-text-tertiary);
  box-shadow: 0 0 0 1px var(--mc-border);
}

.timeline-item.tone-running .timeline-node { background: var(--mc-primary); }
.timeline-item.tone-success .timeline-node { background: #16a34a; }
.timeline-item.tone-warning .timeline-node { background: #d97706; }
.timeline-item.tone-danger .timeline-node { background: #dc2626; }

.timeline-body {
  min-width: 0;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
  padding: 10px 12px;
}

.timeline-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.timeline-line strong {
  color: var(--mc-text-primary);
  font-size: 13px;
}

.timeline-line span {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.timeline-body p {
  margin: 6px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.timeline-fields {
  display: grid;
  grid-template-columns: minmax(72px, auto) minmax(0, 1fr);
  gap: 4px 8px;
  margin: 8px 0 0;
  font-size: 12px;
}

.timeline-fields dt {
  color: var(--mc-text-tertiary);
}

.timeline-fields dd {
  margin: 0;
  color: var(--mc-text-secondary);
  word-break: break-word;
}

.empty-block,
.empty-mini {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 96px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  text-align: center;
}

.empty-mini {
  min-height: 54px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
}

.final-summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.final-table-wrap {
  overflow: auto;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
}

table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
}

th,
td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border);
  color: var(--mc-text-primary);
  font-size: 13px;
  text-align: left;
  vertical-align: top;
}

th {
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  font-weight: 700;
}

tr:last-child td {
  border-bottom: 0;
}

.technical-detail summary {
  cursor: pointer;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.technical-detail :deep(.douyin-result) {
  margin-top: 14px;
}

@media (max-width: 1100px) {
  .live-layout {
    grid-template-columns: 1fr;
  }

  .status-panel {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .lead-live-panel {
    padding: 14px;
  }

  .live-header,
  .live-actions,
  .status-panel {
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
