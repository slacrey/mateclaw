<template>
  <section class="final-summary">
    <div class="section-head">
      <div>
        <h3>最终汇总</h3>
        <p>{{ finalSummaryText }}</p>
      </div>
    </div>

    <DouyinLeadRunResult
      :run="normalizedRun"
      :comments="normalizedRun?.comments ?? []"
      :engagements="normalizedRun?.engagements ?? []"
      :profiles="[]"
      :loading="loading"
    />
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DouyinLeadAcquisitionRunResponse, DouyinLeadRunSummary } from '@/api'
import DouyinLeadRunResult from '@/components/lead/DouyinLeadRunResult.vue'

type JsonRecord = Record<string, unknown>

const props = withDefaults(defineProps<{
  run: DouyinLeadAcquisitionRunResponse | null
  loading?: boolean
}>(), {
  loading: false,
})

const normalizedRun = computed<DouyinLeadAcquisitionRunResponse | null>(() => {
  if (!props.run) return null
  return {
    ...props.run,
    comments: props.run.comments ?? [],
    matches: props.run.matches ?? [],
    engagements: props.run.engagements ?? [],
    events: props.run.events ?? [],
  }
})

const events = computed(() => normalizedRun.value?.events ?? [])

const summaryPayload = computed<JsonRecord>(() => {
  const event = [...events.value].reverse().find(item => item.type === 'lead.run.summary')
  const payload = parsePayload(event?.payloadJson)
  const nested = recordValue(payload.summary)
  return nested ? { ...payload, ...nested } : payload
})

const structuredSummary = computed<Partial<DouyinLeadRunSummary>>(() => {
  return normalizedRun.value?.summary ?? normalizedRun.value?.runSummary ?? {}
})

const runMetrics = computed(() => ({
  requestedVideos: firstNumber(
    normalizedRun.value?.requestedVideoLimit,
    structuredSummary.value.requestedVideoLimit,
    summaryPayload.value.requestedVideoLimit,
    summaryPayload.value.videoLimit,
  ),
  processedVideos: firstNumber(
    normalizedRun.value?.processedVideos,
    structuredSummary.value.processedVideos,
    summaryPayload.value.processedVideos,
  ),
  commentsCollected: firstNumber(
    normalizedRun.value?.commentsCollected,
    structuredSummary.value.commentsCollected,
    summaryPayload.value.commentsCollected,
    normalizedRun.value?.comments?.length,
  ),
  matchedComments: firstNumber(
    normalizedRun.value?.matchedComments,
    structuredSummary.value.matchedComments,
    summaryPayload.value.matchedComments,
    normalizedRun.value?.matches?.length,
  ),
  engagementsCreated: firstNumber(
    normalizedRun.value?.engagementsCreated,
    structuredSummary.value.engagementsCreated,
    summaryPayload.value.engagementsCreated,
    normalizedRun.value?.engagements?.length,
  ),
}))

const finalSummaryText = computed(() => {
  return [
    `处理视频 ${countLabel(runMetrics.value.processedVideos)} / ${countLabel(runMetrics.value.requestedVideos)}`,
    `采集评论 ${countLabel(runMetrics.value.commentsCollected)} 条`,
    `匹配 ${countLabel(runMetrics.value.matchedComments)} 条`,
    `触达 ${countLabel(runMetrics.value.engagementsCreated)} 次`,
  ].join('，')
})

function parsePayload(raw: string | null | undefined): JsonRecord {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw)
    return recordValue(parsed) ?? {}
  } catch {
    return {}
  }
}

function recordValue(value: unknown): JsonRecord | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonRecord : null
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
</script>

<style scoped>
.final-summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 14px;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.section-head h3 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 15px;
}

.section-head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.final-summary :deep(.result-grid--bottom) {
  grid-template-columns: 1fr;
}

.final-summary :deep(.result-grid--bottom .result-panel) {
  min-height: 0;
}

@media (min-width: 1540px) {
  .final-summary :deep(.result-grid--bottom) {
    grid-template-columns: repeat(2, minmax(420px, 1fr));
  }
}
</style>
