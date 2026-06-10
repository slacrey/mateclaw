<template>
  <div class="mc-page-shell lead-shell">
    <div class="mc-page-frame lead-frame">
      <div class="lead-page">
        <header class="lead-header">
          <div>
            <span class="lead-kicker">获客专家</span>
            <h1>抖音获客</h1>
            <p>输入目标关键词和匹配条件，系统会完成搜索、评论采集、评论匹配和触达结果汇总。</p>
          </div>
          <div class="lead-header__actions">
            <button class="ghost-button" type="button" @click="openChatStarter">
              <el-icon><ChatDotRound /></el-icon>
              <span>用对话启动</span>
            </button>
            <button class="ghost-button" type="button" @click="focusBrowserPanel">
              <el-icon><Connection /></el-icon>
              <span>浏览器连接</span>
            </button>
          </div>
        </header>

        <section class="lead-overview">
          <button
            v-for="channel in channels"
            :key="channel.key"
            class="channel-tab"
            :class="{ 'is-active': channel.key === activeChannel }"
            type="button"
            @click="activeChannel = channel.key"
          >
            <span class="channel-tab__icon">
              <el-icon><component :is="channel.icon" /></el-icon>
            </span>
            <span>
              <strong>{{ channel.title }}</strong>
              <small>{{ channel.subtitle }}</small>
            </span>
          </button>
        </section>

        <section
          ref="browserPanelRef"
          class="browser-pairing-workspace"
          :class="{ 'is-open': browserPanelOpen }"
        >
          <button
            class="browser-pairing-toggle"
            type="button"
            :aria-expanded="browserPanelOpen"
            @click="toggleBrowserPanel"
          >
            <span class="browser-pairing-toggle__main">
              <span class="browser-pairing-toggle__icon">
                <el-icon><Connection /></el-icon>
              </span>
              <span>
                <strong>浏览器连接</strong>
                <small>{{ browserPanelOpen ? '扩展配对与连接状态' : '默认收起，连接异常时展开检查' }}</small>
              </span>
            </span>
            <span class="browser-pairing-toggle__action">
              {{ browserPanelOpen ? '收起' : '展开' }}
              <el-icon class="browser-pairing-toggle__chevron">
                <ArrowDown />
              </el-icon>
            </span>
          </button>

          <Transition name="browser-panel">
            <div v-if="browserPanelOpen" class="browser-pairing-body">
              <BrowserPairingPanel embedded compact />
            </div>
          </Transition>
        </section>

        <section class="lead-workbench">
          <form class="launch-panel" @submit.prevent="submitDouyinRun">
            <div class="panel-head">
              <div>
                <h2>启动任务</h2>
                <p>默认按最多点赞排序，先采集一级评论并匹配正文。</p>
              </div>
              <span class="mode-pill">V2</span>
            </div>

            <div class="field-grid">
              <label class="field field--wide">
                <span>关键词</span>
                <input
                  v-model.trim="form.keyword"
                  type="text"
                  maxlength="120"
                  placeholder="例如：易企秀"
                  :disabled="launching"
                  required
                />
              </label>

              <label class="field">
                <span>排序</span>
                <select v-model="form.sort" :disabled="launching">
                  <option value="most_liked">最多点赞</option>
                  <option value="latest">最新发布</option>
                </select>
              </label>

              <label class="field">
                <span>视频数量</span>
                <input
                  v-model.number="form.videoLimit"
                  type="number"
                  min="1"
                  max="50"
                  :disabled="launching"
                  @change="normalizeVideoLimit"
                />
              </label>

              <label class="field field--wide">
                <span>评论匹配规则</span>
                <textarea
                  v-model.trim="form.commentMatchRule"
                  rows="3"
                  maxlength="500"
                  placeholder="例如：慢出心脏病"
                  :disabled="launching"
                ></textarea>
              </label>

              <label class="field field--wide">
                <span>私信模板</span>
                <textarea
                  v-model.trim="form.dmDraft"
                  rows="3"
                  maxlength="500"
                  placeholder="你好"
                  :disabled="launching || !form.engage"
                ></textarea>
              </label>
            </div>

            <div class="switch-row">
              <label class="switch-item">
                <input v-model="form.engage" type="checkbox" :disabled="launching" />
                <span>匹配后关注并打开私信</span>
              </label>
              <label class="switch-item">
                <input v-model="form.sendDm" type="checkbox" :disabled="launching || !form.engage" />
                <span>自动发送私信</span>
              </label>
            </div>

            <div class="form-actions">
              <button class="primary-button" type="submit" :disabled="!canLaunch">
                <span v-if="launching" class="mini-spinner" aria-hidden="true"></span>
                <el-icon v-else><Promotion /></el-icon>
                <span>{{ launching ? '执行中' : '开始获客' }}</span>
              </button>
              <button class="text-button" type="button" :disabled="launching" @click="resetForm">重置</button>
            </div>
          </form>

          <aside class="assist-panel">
            <section class="assist-section">
              <h2>常用模板</h2>
              <div class="template-list">
                <button
                  v-for="template in templates"
                  :key="template.name"
                  type="button"
                  class="template-item"
                  :disabled="launching"
                  @click="applyTemplate(template)"
                >
                  <strong>{{ template.name }}</strong>
                  <span>{{ template.keyword }} · {{ template.match }}</span>
                </button>
              </div>
            </section>

            <section class="assist-section">
              <h2>执行环境</h2>
              <div class="readiness-list">
                <div class="readiness-row">
                  <span>浏览器扩展</span>
                  <button type="button" @click="openBrowserPanel">检查连接</button>
                </div>
                <div class="readiness-row">
                  <span>对话模式</span>
                  <button type="button" @click="openChatStarter">生成指令</button>
                </div>
                <div class="readiness-row">
                  <span>结果统计</span>
                  <button type="button" :disabled="!currentRun?.runId" @click="openRunDetail">查看详情</button>
                </div>
              </div>
            </section>
          </aside>
        </section>

        <section v-if="currentRun || launchError" class="result-panel">
          <div class="panel-head">
            <div>
              <h2>执行汇总</h2>
              <p v-if="currentRun">Run {{ currentRun.runId || '-' }} · {{ statusLabel(currentRun.status) }}</p>
              <p v-else>任务未完成</p>
            </div>
            <button v-if="currentRun?.runId" class="ghost-button" type="button" @click="openRunDetail">
              <el-icon><DataAnalysis /></el-icon>
              <span>打开详情</span>
            </button>
          </div>

          <div v-if="launchError" class="error-block">{{ launchError }}</div>

          <template v-if="currentRun">
            <div class="metric-grid">
              <div v-for="metric in summaryMetrics" :key="metric.label" class="metric-item">
                <span>{{ metric.label }}</span>
                <strong>{{ metric.value }}</strong>
              </div>
            </div>

            <div class="result-tables">
              <section>
                <h3>视频进度</h3>
                <div class="table-wrap">
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
                      <tr v-for="video in videoRows" :key="String(video.videoIndex ?? video.videoNumber ?? video.title)">
                        <td>{{ video.videoNumber ?? Number(video.videoIndex ?? 0) + 1 }}</td>
                        <td>{{ statusLabel(String(video.status || '-')) }}</td>
                        <td>{{ formatCount(video.commentsCollected) }} / {{ formatCount(video.declaredCommentCount) }}</td>
                        <td>{{ formatCount(video.matchedComments) }}</td>
                        <td>{{ video.stopReason || video.failureCode || '-' }}</td>
                      </tr>
                      <tr v-if="!videoRows.length">
                        <td colspan="5">暂无视频明细</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section>
                <h3>触达状态</h3>
                <div class="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>线索</th>
                        <th>状态</th>
                        <th>私信</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="engagement in currentRun.engagements" :key="engagement.id">
                        <td>{{ profileName(engagement.profileId) }}</td>
                        <td>{{ engagement.sent ? '已发送' : statusLabel(engagement.status) }}</td>
                        <td>{{ engagement.draftText || engagement.failureMessage || '-' }}</td>
                      </tr>
                      <tr v-if="!currentRun.engagements?.length">
                        <td colspan="3">暂无触达记录</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>
            </div>

            <details class="technical-detail">
              <summary>技术明细</summary>
              <DouyinLeadRunResult
                :run="currentRun"
                :comments="currentRun.comments"
                :engagements="currentRun.engagements"
                :profiles="[]"
                :loading="launching"
              />
            </details>
          </template>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ChatDotRound, Connection, DataAnalysis, Promotion, Search } from '@element-plus/icons-vue'
import { leadAcquisitionApi } from '@/api'
import type {
  DouyinLeadAcquisitionRunResponse,
  DouyinLeadAcquisitionStartPayload,
  DouyinLeadRunVideoResult,
} from '@/api'
import { mcToast } from '@/composables/useMcToast'
import DouyinLeadRunResult from '@/components/lead/DouyinLeadRunResult.vue'
import BrowserPairingPanel from '@/views/Settings/Browser/index.vue'

type SortMode = 'most_liked' | 'latest'

interface LeadForm {
  keyword: string
  sort: SortMode
  videoLimit: number
  commentMatchRule: string
  dmDraft: string
  engage: boolean
  sendDm: boolean
}

interface LeadTemplate {
  name: string
  keyword: string
  match: string
  dmDraft: string
  videoLimit: number
}

const router = useRouter()
const activeChannel = ref('douyin')
const launching = ref(false)
const currentRun = ref<DouyinLeadAcquisitionRunResponse | null>(null)
const launchError = ref('')
const browserPanelRef = ref<HTMLElement | null>(null)
const browserPanelOpen = ref(false)

const channels = [
  {
    key: 'douyin',
    title: '抖音获客',
    subtitle: '评论采集、匹配、触达',
    icon: Search,
  },
]

const templates: LeadTemplate[] = [
  {
    name: '产品吐槽线索',
    keyword: '易企秀',
    match: '慢出心脏病',
    dmDraft: '你好',
    videoLimit: 2,
  },
  {
    name: '数字化转型需求',
    keyword: 'ai数字化转型',
    match: '转型',
    dmDraft: '你好，看到你对数字化转型有关注，方便交流一下吗？',
    videoLimit: 5,
  },
  {
    name: 'AI 工具咨询',
    keyword: 'AI工具',
    match: '怎么用',
    dmDraft: '你好，看到你在评论里提到 AI 工具，我这边可以分享一个方案。',
    videoLimit: 5,
  },
]

const form = ref<LeadForm>(defaultForm())

const canLaunch = computed(() => {
  return !launching.value && form.value.keyword.trim().length > 0
})

const runSummaryPayload = computed<Record<string, unknown>>(() => {
  const event = [...(currentRun.value?.events ?? [])]
    .reverse()
    .find(item => item.type === 'lead.run.summary')
  if (!event?.payloadJson) return {}
  try {
    return JSON.parse(event.payloadJson) as Record<string, unknown>
  } catch {
    return {}
  }
})

const videoRows = computed<DouyinLeadRunVideoResult[]>(() => {
  const fromSummary = runSummaryPayload.value.videoResults
  if (Array.isArray(fromSummary)) return fromSummary as DouyinLeadRunVideoResult[]
  return []
})

const summaryMetrics = computed(() => {
  const run = currentRun.value
  const payload = runSummaryPayload.value
  return [
    { label: '视频', value: `${firstNumber(run?.processedVideos, payload.processedVideos, 0)} / ${firstNumber(run?.requestedVideoLimit, payload.requestedVideoLimit, form.value.videoLimit)}` },
    { label: '评论', value: `${formatCount(firstNumber(run?.commentsCollected, payload.commentsCollected, 0))} / ${formatCount(firstNumber(run?.declaredCommentCount, payload.declaredCommentCount, 0))}` },
    { label: '匹配', value: formatCount(firstNumber(run?.matchedComments, payload.matchedComments, 0)) },
    { label: '触达', value: formatCount(firstNumber(run?.engagementsCreated, payload.engagementsCreated, run?.engagements?.length, 0)) },
  ]
})

watch(() => form.value.engage, (engage) => {
  if (!engage) form.value.sendDm = false
})

function defaultForm(): LeadForm {
  return {
    keyword: '',
    sort: 'most_liked',
    videoLimit: 2,
    commentMatchRule: '',
    dmDraft: '你好',
    engage: true,
    sendDm: false,
  }
}

function normalizeVideoLimit() {
  const raw = Number(form.value.videoLimit)
  const next = Number.isFinite(raw) ? Math.trunc(raw) : 2
  form.value.videoLimit = Math.min(50, Math.max(1, next))
}

function resetForm() {
  form.value = defaultForm()
  launchError.value = ''
}

function applyTemplate(template: LeadTemplate) {
  form.value = {
    keyword: template.keyword,
    sort: 'most_liked',
    videoLimit: template.videoLimit,
    commentMatchRule: template.match,
    dmDraft: template.dmDraft,
    engage: true,
    sendDm: false,
  }
}

async function submitDouyinRun() {
  if (!canLaunch.value) return
  normalizeVideoLimit()
  if (!form.value.engage) form.value.sendDm = false
  launching.value = true
  launchError.value = ''
  currentRun.value = null
  try {
    const payload: DouyinLeadAcquisitionStartPayload = {
      keyword: form.value.keyword.trim(),
      sort: form.value.sort,
      videoLimit: form.value.videoLimit,
      commentMatchRule: form.value.commentMatchRule.trim(),
      dmDraft: form.value.dmDraft.trim() || '你好',
      engage: form.value.engage,
      sendDm: form.value.sendDm,
    }
    const response = await leadAcquisitionApi.startDouyinRun(payload)
    const run = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    if (!run) throw new Error('获客任务没有返回结果')
    currentRun.value = normalizeRun(run)
    mcToast.success('抖音获客任务已完成')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    launchError.value = message
    mcToast.error(message)
  } finally {
    launching.value = false
  }
}

function normalizeRun(run: DouyinLeadAcquisitionRunResponse): DouyinLeadAcquisitionRunResponse {
  return {
    ...run,
    comments: run.comments ?? [],
    matches: run.matches ?? [],
    engagements: run.engagements ?? [],
    events: run.events ?? [],
  }
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

function firstNumber(...values: unknown[]): number {
  for (const value of values) {
    const num = Number(value)
    if (Number.isFinite(num)) return num
  }
  return 0
}

function formatCount(value: unknown): string {
  const num = firstNumber(value, 0)
  return num.toLocaleString()
}

function statusLabel(value?: string | null): string {
  const status = String(value || '').toLowerCase()
  if (status === 'succeeded') return '成功'
  if (status === 'failed') return '失败'
  if (status === 'running') return '执行中'
  if (status === 'completed') return '完成'
  if (status === 'sent') return '已发送'
  return value || '-'
}

function profileName(profileId?: string | null): string {
  if (!profileId) return '线索'
  return `线索 ${profileId}`
}

function toggleBrowserPanel() {
  browserPanelOpen.value = !browserPanelOpen.value
  if (browserPanelOpen.value) focusBrowserPanel()
}

function openBrowserPanel() {
  browserPanelOpen.value = true
  focusBrowserPanel()
}

async function focusBrowserPanel() {
  browserPanelOpen.value = true
  await nextTick()
  browserPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function openRunDetail() {
  if (!currentRun.value?.runId) return
  router.push({
    name: 'DouyinLeadRunDetail',
    params: { runId: currentRun.value.runId },
    query: currentRun.value.taskId ? { taskId: currentRun.value.taskId } : undefined,
  })
}

function openChatStarter() {
  router.push({
    path: '/chat',
    query: {
      action: 'newChat',
      prompt: buildChatPrompt(),
    },
  })
}

function buildChatPrompt(): string {
  return [
    '我要执行抖音获客。',
    '请先向我确认这些参数：关键词、排序方式、视频数量、评论匹配规则、私信模板、是否关注、是否发送私信。',
    '确认后执行抖音获客 V2，并在结束时汇总每个视频的评论声明数、实际采集数、匹配数，以及每个 engagement 的状态。',
  ].join('\n')
}
</script>

<style scoped>
.lead-shell {
  overflow: auto;
}

.lead-frame {
  min-height: 100%;
}

.lead-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 22px;
}

.lead-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 4px 0 10px;
  border-bottom: 1px solid var(--mc-border);
}

.lead-kicker {
  display: inline-flex;
  margin-bottom: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.lead-header h1 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 28px;
  line-height: 1.2;
}

.lead-header p {
  max-width: 680px;
  margin: 8px 0 0;
  color: var(--mc-text-secondary);
  font-size: 14px;
}

.lead-header__actions,
.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lead-overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.channel-tab,
.template-item,
.ghost-button,
.primary-button,
.text-button {
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-container);
  color: var(--mc-text-primary);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease, transform .18s ease;
}

.channel-tab {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 74px;
  padding: 14px 16px;
  text-align: left;
  border-radius: 8px;
}

.channel-tab.is-active {
  border-color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 8%, var(--mc-bg-container));
}

.channel-tab__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 10%, transparent);
}

.channel-tab strong,
.template-item strong {
  display: block;
  font-size: 14px;
}

.channel-tab small,
.template-item span {
  display: block;
  margin-top: 4px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.lead-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}

.browser-pairing-workspace {
  scroll-margin-top: 18px;
  overflow: hidden;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
}

.browser-pairing-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  width: 100%;
  min-height: 58px;
  border: 0;
  background: transparent;
  color: var(--mc-text-primary);
  cursor: pointer;
  padding: 12px 16px;
  text-align: left;
}

.browser-pairing-toggle:hover {
  background: var(--mc-bg-muted);
}

.browser-pairing-toggle__main {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.browser-pairing-toggle__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 8px;
  color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 10%, transparent);
}

.browser-pairing-toggle strong,
.browser-pairing-toggle small {
  display: block;
}

.browser-pairing-toggle strong {
  font-size: 14px;
}

.browser-pairing-toggle small {
  margin-top: 4px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.browser-pairing-toggle__action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 650;
}

.browser-pairing-toggle__chevron {
  transition: transform .18s ease;
}

.browser-pairing-workspace.is-open .browser-pairing-toggle__chevron {
  transform: rotate(180deg);
}

.browser-pairing-body {
  padding: 0 16px 16px;
  border-top: 1px solid var(--mc-border);
}

.browser-panel-enter-active,
.browser-panel-leave-active {
  transition: opacity .16s ease, transform .16s ease;
}

.browser-panel-enter-from,
.browser-panel-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.launch-panel,
.assist-panel,
.result-panel {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
}

.launch-panel,
.result-panel {
  padding: 18px;
}

.assist-panel {
  display: flex;
  flex-direction: column;
}

.assist-section {
  padding: 16px;
  border-bottom: 1px solid var(--mc-border);
}

.assist-section:last-child {
  border-bottom: 0;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head h2,
.assist-section h2,
.result-tables h3 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 16px;
}

.panel-head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.mode-pill {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 10%, transparent);
  font-size: 12px;
  font-weight: 700;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.field--wide {
  grid-column: 1 / -1;
}

.field input,
.field select,
.field textarea {
  width: 100%;
  min-height: 40px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font: inherit;
  font-weight: 400;
  padding: 9px 11px;
  outline: none;
}

.field textarea {
  resize: vertical;
  line-height: 1.5;
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.switch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 16px 0;
}

.switch-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-text-primary);
  font-size: 13px;
}

.switch-item input {
  width: 16px;
  height: 16px;
}

.primary-button,
.ghost-button,
.text-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: 6px;
  font-weight: 650;
}

.primary-button {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: white;
}

.ghost-button:hover,
.template-item:hover,
.channel-tab:hover {
  border-color: var(--mc-primary);
}

.text-button {
  border-color: transparent;
  background: transparent;
  color: var(--mc-text-secondary);
}

button:disabled {
  cursor: not-allowed;
  opacity: .58;
}

.template-list,
.readiness-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.template-item {
  padding: 12px;
  border-radius: 8px;
  text-align: left;
}

.readiness-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.readiness-row button {
  min-height: 30px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 0 10px;
  cursor: pointer;
}

.error-block {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--mc-danger) 40%, var(--mc-border));
  border-radius: 8px;
  color: var(--mc-danger);
  background: color-mix(in srgb, var(--mc-danger) 8%, transparent);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}

.metric-item {
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
}

.metric-item span {
  display: block;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.metric-item strong {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-primary);
  font-size: 20px;
}

.result-tables {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr);
  gap: 16px;
}

.result-tables h3 {
  margin-bottom: 10px;
}

.table-wrap {
  overflow: auto;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
}

table {
  width: 100%;
  border-collapse: collapse;
  min-width: 520px;
}

th,
td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border);
  text-align: left;
  vertical-align: top;
  font-size: 13px;
}

th {
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  font-weight: 650;
}

tr:last-child td {
  border-bottom: 0;
}

.technical-detail {
  margin-top: 16px;
}

.technical-detail summary {
  cursor: pointer;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 650;
}

.technical-detail :deep(.douyin-result) {
  margin-top: 14px;
}

.mini-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, .55);
  border-top-color: white;
  border-radius: 999px;
  animation: spin .8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1080px) {
  .lead-workbench,
  .result-tables {
    grid-template-columns: 1fr;
  }

  .assist-panel {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .assist-section {
    border-bottom: 0;
    border-right: 1px solid var(--mc-border);
  }

  .assist-section:last-child {
    border-right: 0;
  }
}

@media (max-width: 720px) {
  .lead-page {
    padding: 14px;
  }

  .lead-header {
    align-items: stretch;
    flex-direction: column;
  }

  .lead-header__actions,
  .form-actions {
    width: 100%;
  }

  .ghost-button,
  .primary-button {
    flex: 1;
  }

  .field-grid,
  .assist-panel,
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .assist-section {
    border-right: 0;
    border-bottom: 1px solid var(--mc-border);
  }
}
</style>
