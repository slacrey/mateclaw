<template>
  <section class="douyin-detail">
    <header class="detail-header">
      <div class="detail-heading">
        <span class="detail-kicker">抖音获客</span>
        <h2>{{ heading }}</h2>
        <div class="detail-meta">
          <span v-if="run?.status" class="status-pill" :class="runStatusClass">{{ statusLabel(run.status) }}</span>
          <span v-if="run?.taskId" class="meta-code">子任务 {{ run.taskId }}</span>
          <span v-if="run?.runId" class="meta-code">任务 {{ run.runId }}</span>
        </div>
      </div>
      <div class="detail-actions">
        <button class="action-button" :disabled="loading" @click="refresh">
          <el-icon :class="{ 'is-loading': loading }"><Refresh /></el-icon>
          <span>刷新</span>
        </button>
      </div>
    </header>

    <div v-if="loadError" class="state-block state-block--error">
      <span class="state-mark">!</span>
      <p>{{ loadError }}</p>
      <button class="action-button" @click="refresh">
        <el-icon><Refresh /></el-icon>
        <span>重试</span>
      </button>
    </div>

    <div v-else-if="!run && loading" class="state-block">
      <span class="spinner" aria-hidden="true" />
      <p>正在加载抖音获客任务...</p>
    </div>

    <div v-else-if="!run" class="state-block">
      <span class="state-mark state-mark--empty" aria-hidden="true" />
      <p>请选择一个抖音获客任务查看结果。</p>
    </div>

    <template v-else>
      <div v-if="relatedError" class="related-warning">
        {{ relatedError }}
      </div>
      <DouyinLeadRunResult
        :run="run"
        :comments="comments"
        :profiles="profiles"
        :engagements="engagements"
        :loading="loading || relatedLoading"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import {
  leadAcquisitionApi,
  type DouyinLeadAcquisitionRunResponse,
  type DouyinLeadComment,
  type DouyinLeadEngagement,
  type DouyinLeadProfile,
} from '@/api'
import DouyinLeadRunResult from './DouyinLeadRunResult.vue'

const props = withDefaults(defineProps<{
  runId?: string | number | null
  taskId?: string | number | null
  initialRun?: DouyinLeadAcquisitionRunResponse | null
  autoLoad?: boolean
}>(), {
  runId: null,
  taskId: null,
  initialRun: null,
  autoLoad: true,
})

const emit = defineEmits<{
  loaded: [run: DouyinLeadAcquisitionRunResponse]
  error: [message: string]
}>()

const run = ref<DouyinLeadAcquisitionRunResponse | null>(props.initialRun)
const comments = ref<DouyinLeadComment[]>(props.initialRun?.comments ?? [])
const profiles = ref<DouyinLeadProfile[]>([])
const engagements = ref<DouyinLeadEngagement[]>(props.initialRun?.engagements ?? [])
const loading = ref(false)
const relatedLoading = ref(false)
const loadError = ref('')
const relatedError = ref('')

const heading = computed(() => {
  if (run.value?.runId) return `任务 ${run.value.runId}`
  if (props.runId) return `任务 ${props.runId}`
  if (props.taskId) return `子任务 ${props.taskId}`
  return '任务详情'
})

const runStatusClass = computed(() => {
  const status = (run.value?.status || '').toLowerCase()
  if (status === 'succeeded' || status === 'completed') return 'status-succeeded'
  if (status === 'failed' || status === 'error') return 'status-failed'
  if (status === 'running') return 'status-running'
  return 'status-muted'
})

async function refresh() {
  loadError.value = ''
  const taskTarget = props.taskId ?? run.value?.taskId ?? null
  const runTarget = props.runId ?? run.value?.runId ?? null

  if (!taskTarget && !runTarget) {
    if (run.value?.taskId) await loadRelated(run.value.taskId)
    return
  }

  loading.value = true
  try {
    const response = taskTarget
      ? await leadAcquisitionApi.getDouyinTask(taskTarget)
      : await leadAcquisitionApi.getDouyinRun(runTarget as string | number)
    const nextRun = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    if (!nextRun) throw new Error('没有返回抖音获客任务。')

    run.value = normalizeRun(nextRun)
    comments.value = run.value.comments ?? []
    engagements.value = run.value.engagements ?? []
    emit('loaded', run.value)

    if (run.value.taskId) {
      await loadRelated(run.value.taskId)
    } else {
      profiles.value = []
      relatedError.value = ''
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    loadError.value = message
    emit('error', message)
  } finally {
    loading.value = false
  }
}

async function loadRelated(taskId: string | number) {
  relatedLoading.value = true
  relatedError.value = ''
  try {
    const [commentResponse, profileResponse, engagementResponse] = await Promise.all([
      leadAcquisitionApi.listDouyinComments(taskId),
      leadAcquisitionApi.listDouyinProfiles(taskId),
      leadAcquisitionApi.listDouyinEngagements(taskId),
    ])
    comments.value = unwrapApiData<DouyinLeadComment[]>(commentResponse, run.value?.comments ?? [])
    profiles.value = unwrapApiData<DouyinLeadProfile[]>(profileResponse, [])
    engagements.value = unwrapApiData<DouyinLeadEngagement[]>(engagementResponse, run.value?.engagements ?? [])
  } catch (error) {
    comments.value = run.value?.comments ?? []
    engagements.value = run.value?.engagements ?? []
    profiles.value = []
    relatedError.value = `关联线索数据加载失败：${error instanceof Error ? error.message : String(error)}`
  } finally {
    relatedLoading.value = false
  }
}

function unwrapApiData<T>(response: unknown, fallback: T): T {
  if (!response || typeof response !== 'object') return fallback
  const candidate = response as { data?: unknown }
  if ('data' in candidate) return (candidate.data ?? fallback) as T
  return response as T
}

function normalizeRun(value: DouyinLeadAcquisitionRunResponse): DouyinLeadAcquisitionRunResponse {
  return {
    ...value,
    comments: value.comments ?? [],
    matches: value.matches ?? [],
    engagements: value.engagements ?? [],
    events: value.events ?? [],
  }
}

function statusLabel(value?: string | null): string {
  const normalized = String(value || '').toLowerCase()
  if (normalized === 'running') return '执行中'
  if (normalized === 'created') return '已创建'
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed') return '成功'
  if (normalized === 'failed' || normalized === 'error') return '失败'
  if (normalized === 'aborted' || normalized === 'cancelled' || normalized === 'canceled') return '已停止'
  return value || '-'
}

watch(() => props.initialRun, (value) => {
  if (!value) return
  run.value = normalizeRun(value)
  comments.value = run.value.comments ?? []
  engagements.value = run.value.engagements ?? []
  if (run.value.taskId) {
    void loadRelated(run.value.taskId)
  }
}, { immediate: true })

watch(() => [props.runId, props.taskId] as const, () => {
  if (props.autoLoad) void refresh()
})

onMounted(() => {
  if (props.autoLoad) void refresh()
})

defineExpose({ refresh })
</script>

<style scoped>
.douyin-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
}

.detail-heading {
  min-width: 0;
}

.detail-kicker {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
}

.detail-heading h2 {
  margin: 0;
  font-size: 18px;
  line-height: 1.3;
  color: var(--mc-text-primary);
  word-break: break-word;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.action-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 34px;
  padding: 7px 12px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  color: var(--mc-text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.action-button:hover:not(:disabled) {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
}

.action-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.is-loading {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.status-succeeded {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.status-failed {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.status-running {
  background: rgba(37, 99, 235, 0.12);
  color: #1d4ed8;
}

.status-muted {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.05));
  color: var(--mc-text-secondary);
}

.meta-code {
  max-width: min(100%, 320px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 240px;
  padding: 32px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  text-align: center;
  color: var(--mc-text-secondary);
}

.state-block p {
  margin: 0;
  font-size: 14px;
}

.state-block--error {
  border-color: rgba(220, 38, 38, 0.24);
  color: var(--mc-danger, #b91c1c);
}

.state-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
  font-weight: 800;
}

.state-mark--empty {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.05));
}

.spinner {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-top-color: var(--mc-primary, #2563eb);
  animation: spin 0.8s linear infinite;
}

.related-warning {
  padding: 10px 12px;
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: 8px;
  background: rgba(217, 119, 6, 0.08);
  color: #92400e;
  font-size: 13px;
  line-height: 1.5;
}

@media (max-width: 720px) {
  .detail-header {
    flex-direction: column;
  }

  .detail-actions,
  .action-button {
    width: 100%;
  }
}
</style>
