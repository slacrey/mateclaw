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
            </div>

            <div class="field-grid">
              <label class="field field--wide">
                <span>关键词</span>
                <input
                  v-model.trim="form.keyword"
                  type="text"
                  maxlength="120"
                  placeholder="例如：易企秀"
                  :disabled="taskLocked"
                  required
                />
              </label>

              <label class="field">
                <span>排序</span>
                <select v-model="form.sort" :disabled="taskLocked">
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
                  :disabled="taskLocked"
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
                  :disabled="taskLocked"
                ></textarea>
              </label>

              <label class="field field--wide">
                <span>私信模板</span>
                <textarea
                  v-model.trim="form.dmDraft"
                  rows="3"
                  maxlength="500"
                  placeholder="你好"
                  :disabled="taskLocked || !form.engage"
                ></textarea>
              </label>
            </div>

            <div class="switch-row">
              <label class="switch-item">
                <input v-model="form.engage" type="checkbox" :disabled="taskLocked" />
                <span>匹配后关注并打开私信</span>
              </label>
              <label class="switch-item">
                <input v-model="form.sendDm" type="checkbox" :disabled="taskLocked || !form.engage" />
                <span>自动发送私信</span>
              </label>
            </div>

            <div class="form-actions">
              <button class="primary-button" type="submit" :disabled="!canLaunch">
                <span v-if="launching" class="mini-spinner" aria-hidden="true"></span>
                <el-icon v-else><Promotion /></el-icon>
                <span>{{ launchButtonText }}</span>
              </button>
              <button class="text-button" type="button" :disabled="taskLocked" @click="resetForm">重置</button>
            </div>
          </form>

          <aside class="assist-panel">
            <section class="assist-section">
              <div class="assist-section__head">
                <h2>常用模板</h2>
                <button type="button" :disabled="templateSaving" @click="saveCurrentTemplate">
                  {{ templateSaving ? '保存中' : '保存当前' }}
                </button>
              </div>
              <label class="template-name-field">
                <span>模板名称</span>
                <input v-model.trim="templateName" type="text" maxlength="60" placeholder="例如：产品吐槽线索" />
              </label>
              <div class="template-list">
                <template v-if="savedTemplates.length">
                  <div
                    v-for="template in savedTemplates"
                    :key="template.id"
                    class="template-item template-item--saved"
                  >
                    <button type="button" :disabled="taskLocked" @click="applyTemplate(template)">
                      <strong>{{ template.name }}</strong>
                      <span>{{ template.keyword || '未设置关键词' }} · {{ template.commentMatchRule || '只采集评论' }}</span>
                    </button>
                    <button type="button" class="template-delete" @click="deleteTemplate(template.id)">删除</button>
                  </div>
                </template>
                <button
                  v-for="template in builtInTemplates"
                  :key="template.name"
                  type="button"
                  class="template-item"
                  :disabled="taskLocked"
                  @click="applyTemplate(template)"
                >
                  <strong>{{ template.name }}</strong>
                  <span>{{ template.keyword }} · {{ template.commentMatchRule }}</span>
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
          <div v-if="launchError" class="error-block">{{ launchError }}</div>
          <LeadRunLivePanel
            v-if="currentRun"
            :run-id="currentRun.runId"
            :task-id="currentRun.taskId"
            :initial-run="currentRun"
            @update:run="handleLiveRunUpdate"
            @terminal="handleRunTerminal"
          />
        </section>

        <section class="stats-panel">
          <div class="panel-head compact">
            <div>
              <h2>统计看板</h2>
              <p>{{ statsLoading ? '正在更新最近任务表现...' : '汇总最近获客任务的采集、命中和触达效果。' }}</p>
            </div>
            <button class="text-button" type="button" :disabled="statsLoading" @click="loadLeadStats()">
              {{ statsLoading ? '刷新中' : '刷新' }}
            </button>
          </div>

          <div class="stats-toolbar">
            <label>
              <span>关键词筛选</span>
              <input
                v-model.trim="statsKeyword"
                type="text"
                placeholder="默认统计最近 50 个任务"
                @keyup.enter="loadLeadStats()"
              />
            </label>
            <button class="text-button" type="button" @click="clearStatsKeyword">清空</button>
          </div>
          <div v-if="statsError" class="stats-error">{{ statsError }}</div>

          <div class="stats-card-grid">
            <article v-for="card in statsCards" :key="card.label" class="metric-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <small>{{ card.detail }}</small>
            </article>
          </div>

          <div class="stats-insights">
            <div class="rate-strip">
              <div v-for="rate in statsRates" :key="rate.label" class="rate-item">
                <span>{{ rate.label }}</span>
                <strong>{{ rate.value }}</strong>
                <small>{{ rate.detail }}</small>
              </div>
            </div>
            <div class="failure-card">
              <div class="failure-card__head">
                <h3>失败原因分布</h3>
                <span>{{ failureReasonRows.length }} 类</span>
              </div>
              <div v-if="failureReasonRows.length" class="failure-list">
                <div v-for="reason in failureReasonRows" :key="reason.rawReason" class="failure-row">
                  <span>{{ reason.label }}</span>
                  <strong>{{ reason.count }}</strong>
                  <div class="failure-bar" aria-hidden="true">
                    <i :style="{ width: reason.percent }"></i>
                  </div>
                </div>
              </div>
              <div v-else class="empty-inline">最近任务暂无失败记录。</div>
            </div>
          </div>
        </section>

        <section class="growth-workspace">
          <section class="history-panel">
            <div class="panel-head compact">
              <div>
                <h2>最近任务</h2>
                <p>查看历史获客任务，继续打开实时执行台和结果明细。</p>
              </div>
              <button class="text-button" type="button" :disabled="recentLoading" @click="loadRecentRuns">
                {{ recentLoading ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div v-if="recentRuns.length" class="history-list">
              <button
                v-for="run in recentRuns"
                :key="run.runId || run.taskId || run.createTime || 'run'"
                class="history-item"
                type="button"
                :class="{ 'is-active': run.runId && run.runId === currentRun?.runId }"
                @click="openRunFromHistory(run)"
              >
                <span class="history-item__main">
                  <strong>{{ run.keyword || '抖音获客任务' }}</strong>
                  <small>{{ sortLabel(run.sort) }} · {{ formatDateTime(run.updateTime || run.createTime) }}</small>
                </span>
                <span class="history-item__stats">
                  <span>{{ statusLabel(run.status) }}</span>
                  <small>{{ run.processedVideos || 0 }}/{{ run.requestedVideoLimit || '-' }} 视频 · {{ run.commentsCollected || 0 }} 评论 · {{ run.matchedComments || 0 }} 命中</small>
                </span>
              </button>
            </div>
            <div v-else class="empty-card">
              {{ recentLoading ? '正在读取最近任务...' : '暂无历史任务。启动一次获客后会显示在这里。' }}
            </div>
          </section>

          <section class="lead-pool-panel">
            <div class="panel-head compact">
              <div>
                <h2>线索池</h2>
                <p>{{ leadPoolLoading ? '正在读取跨任务线索...' : `展示 ${leadPoolRows.length} 条跨任务命中线索。` }}</p>
              </div>
              <button class="text-button" type="button" :disabled="leadPoolLoading" @click="loadLeadPool">
                {{ leadPoolLoading ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div class="lead-pool-filters">
              <label>
                <span>关键词</span>
                <input
                  v-model.trim="leadPoolKeyword"
                  type="text"
                  placeholder="按任务关键词筛选"
                  @keyup.enter="loadLeadPool"
                />
              </label>
              <label>
                <span>状态</span>
                <select v-model="leadPoolStatus" @change="loadLeadPool">
                  <option value="all">全部线索</option>
                  <option value="pending">待触达</option>
                  <option value="engaged">已触达</option>
                  <option value="sent">已发送</option>
                  <option value="failed">失败</option>
                </select>
              </label>
            </div>

            <div v-if="leadPoolRows.length" class="lead-pool-list">
              <article v-for="lead in leadPoolRows" :key="lead.key" class="lead-card">
                <div class="lead-card__top">
                  <a
                    v-if="lead.authorProfileUrl"
                    :href="lead.authorProfileUrl"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {{ lead.authorName }}
                  </a>
                  <strong v-else>{{ lead.authorName }}</strong>
                  <span class="status-pill" :class="lead.sent ? 'status-succeeded' : engagementTone(lead.engagementStatus)">
                    {{ lead.sent ? '已发送' : statusLabel(lead.engagementStatus) }}
                  </span>
                </div>
                <p>{{ lead.text }}</p>
                <div class="lead-card__meta">
                  <span>{{ lead.videoKey || '来源视频未记录' }}</span>
                  <span>{{ lead.keyword || '未知关键词' }}</span>
                  <span v-if="lead.failureReason">{{ lead.failureReason }}</span>
                  <button v-if="lead.runId" class="inline-link" type="button" @click="openRunById(lead.runId)">
                    回看任务
                  </button>
                </div>
              </article>
            </div>
            <div v-else class="empty-card">
              {{ leadPoolLoading ? '正在读取线索池...' : '暂无命中线索。' }}
            </div>
          </section>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ChatDotRound, Connection, Promotion, Search } from '@element-plus/icons-vue'
import { leadAcquisitionApi } from '@/api'
import type {
  DouyinLeadAcquisitionRunResponse,
  DouyinLeadAcquisitionStartPayload,
  DouyinLeadPoolItem,
  DouyinLeadRunListItem,
  DouyinLeadStatsResponse,
  DouyinLeadTemplate,
  DouyinLeadTemplatePayload,
} from '@/api'
import { mcToast } from '@/composables/useMcToast'
import LeadRunLivePanel from '@/components/lead/LeadRunLivePanel.vue'
import BrowserPairingPanel from '@/views/Settings/Browser/index.vue'

type SortMode = 'most_liked' | 'latest'
type LeadPoolStatus = 'all' | 'pending' | 'engaged' | 'sent' | 'failed'

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
  commentMatchRule: string
  dmDraft: string
  videoLimit: number
  sort?: SortMode
  engage?: boolean
  sendDm?: boolean
}

const router = useRouter()
const activeChannel = ref('douyin')
const launching = ref(false)
const currentRun = ref<DouyinLeadAcquisitionRunResponse | null>(null)
const launchError = ref('')
const browserPanelRef = ref<HTMLElement | null>(null)
const browserPanelOpen = ref(false)
const recentRuns = ref<DouyinLeadRunListItem[]>([])
const recentLoading = ref(false)
const leadPool = ref<DouyinLeadPoolItem[]>([])
const leadPoolLoading = ref(false)
const leadPoolKeyword = ref('')
const leadPoolStatus = ref<LeadPoolStatus>('all')
const leadStats = ref<DouyinLeadStatsResponse | null>(null)
const statsLoading = ref(false)
const statsReloadQueued = ref(false)
const statsError = ref('')
const statsKeyword = ref('')
const savedTemplates = ref<DouyinLeadTemplate[]>([])
const templateName = ref('')
const templateSaving = ref(false)

const channels = [
  {
    key: 'douyin',
    title: '抖音获客',
    subtitle: '评论采集、匹配、触达',
    icon: Search,
  },
]

const builtInTemplates: LeadTemplate[] = [
  {
    name: '产品吐槽线索',
    keyword: '易企秀',
    commentMatchRule: '慢出心脏病',
    dmDraft: '你好',
    videoLimit: 2,
  },
  {
    name: '数字化转型需求',
    keyword: 'ai数字化转型',
    commentMatchRule: '转型',
    dmDraft: '你好，看到你对数字化转型有关注，方便交流一下吗？',
    videoLimit: 5,
  },
  {
    name: 'AI 工具咨询',
    keyword: 'AI工具',
    commentMatchRule: '怎么用',
    dmDraft: '你好，看到你在评论里提到 AI 工具，我这边可以分享一个方案。',
    videoLimit: 5,
  },
]

const form = ref<LeadForm>(defaultForm())

const canLaunch = computed(() => {
  return !taskLocked.value && form.value.keyword.trim().length > 0
})

const activeRunRunning = computed(() => {
  return !!currentRun.value && !isTerminalStatus(currentRun.value.status)
})

const taskLocked = computed(() => launching.value || activeRunRunning.value)

const launchButtonText = computed(() => {
  if (launching.value) return '启动中'
  if (activeRunRunning.value) return '执行中'
  return '开始获客'
})

const leadPoolRows = computed(() => {
  return leadPool.value.map((lead, index) => ({
    key: lead.commentId || lead.commentKey || `${lead.text}-${index}`,
    runId: lead.runId,
    keyword: lead.keyword,
    authorName: lead.displayName || lead.authorName || '未识别作者',
    authorProfileUrl: lead.profileUrl || lead.authorProfileUrl,
    text: lead.text || '-',
    videoKey: lead.videoKey,
    engagementStatus: lead.engagementStatus || 'pending',
    sent: lead.sent === true,
    failureReason: reasonLabel(lead.failureCode || lead.failureMessage || ''),
  }))
})

const statsCards = computed(() => {
  const stats = leadStats.value ?? emptyStats()
  return [
    {
      label: '任务',
      value: stats.taskCount,
      detail: `成功 ${stats.succeededTasks} · 执行中 ${stats.runningTasks} · 失败 ${stats.failedTasks}`,
    },
    {
      label: '视频',
      value: `${stats.processedVideos}/${stats.requestedVideos || '-'}`,
      detail: `成功 ${stats.succeededVideos} · 失败 ${stats.failedVideos}`,
    },
    {
      label: '评论',
      value: stats.commentsCollected,
      detail: `命中 ${stats.matchedComments} 条评论`,
    },
    {
      label: '触达',
      value: stats.engagementsCreated,
      detail: `已发送 ${stats.sentMessages} 条私信`,
    },
  ]
})

const statsRates = computed(() => {
  const stats = leadStats.value ?? emptyStats()
  return [
    {
      label: '评论命中率',
      value: formatPercent(stats.matchRate),
      detail: `${stats.matchedComments}/${stats.commentsCollected || 0}`,
    },
    {
      label: '触达率',
      value: formatPercent(stats.engagementRate),
      detail: `${stats.engagementsCreated}/${stats.matchedComments || 0}`,
    },
    {
      label: '发送成功率',
      value: formatPercent(stats.sendSuccessRate),
      detail: `${stats.sentMessages}/${stats.engagementsCreated || 0}`,
    },
  ]
})

const failureReasonRows = computed(() => {
  const rows = leadStats.value?.failureReasons ?? []
  const total = rows.reduce((sum, item) => sum + Math.max(0, item.count || 0), 0)
  return rows.map((item) => {
    const count = Math.max(0, item.count || 0)
    return {
      rawReason: item.reason || 'UNKNOWN_FAILURE',
      label: reasonLabel(item.reason),
      count,
      percent: total > 0 ? `${Math.max(6, Math.round((count / total) * 100))}%` : '0%',
    }
  })
})

watch(() => form.value.engage, (engage) => {
  if (!engage) form.value.sendDm = false
})

onMounted(() => {
  loadRecentRuns()
  loadLeadPool()
  loadLeadStats()
  loadTemplates()
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

function applyTemplate(template: LeadTemplate | DouyinLeadTemplate) {
  form.value = {
    keyword: template.keyword || '',
    sort: normalizeSortMode(template.sort),
    videoLimit: template.videoLimit || 2,
    commentMatchRule: 'commentMatchRule' in template ? (template.commentMatchRule || '') : '',
    dmDraft: template.dmDraft || '你好',
    engage: template.engage ?? true,
    sendDm: template.sendDm ?? false,
  }
  templateName.value = template.name || ''
}

async function loadTemplates() {
  try {
    const response = await leadAcquisitionApi.listDouyinTemplates()
    savedTemplates.value = unwrapApiData<DouyinLeadTemplate[]>(response, [])
  } catch (error) {
    savedTemplates.value = savedTemplates.value ?? []
  }
}

async function saveCurrentTemplate() {
  if (templateSaving.value) return
  const name = templateName.value.trim() || form.value.keyword.trim() || '抖音获客模板'
  templateSaving.value = true
  try {
    const payload = templatePayload(name)
    const existing = savedTemplates.value.find(item => item.name === name)
    if (existing?.id) {
      await leadAcquisitionApi.updateDouyinTemplate(existing.id, payload)
    } else {
      await leadAcquisitionApi.createDouyinTemplate(payload)
    }
    await loadTemplates()
    templateName.value = name
    mcToast.success('模板已保存')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  } finally {
    templateSaving.value = false
  }
}

async function deleteTemplate(id: string | number) {
  try {
    await leadAcquisitionApi.deleteDouyinTemplate(id)
    savedTemplates.value = savedTemplates.value.filter(item => item.id !== String(id))
    mcToast.success('模板已删除')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  }
}

function templatePayload(name: string): DouyinLeadTemplatePayload {
  normalizeVideoLimit()
  if (!form.value.engage) form.value.sendDm = false
  return {
    name,
    keyword: form.value.keyword.trim(),
    sort: form.value.sort,
    videoLimit: form.value.videoLimit,
    commentMatchRule: form.value.commentMatchRule.trim(),
    dmDraft: form.value.dmDraft.trim() || '你好',
    engage: form.value.engage,
    sendDm: form.value.sendDm,
  }
}

function normalizeSortMode(value?: string | null): SortMode {
  return value === 'latest' ? 'latest' : 'most_liked'
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
    await loadRecentRuns()
    await loadLeadPool()
    await loadLeadStats({ force: true })
    mcToast.success('抖音获客任务已启动')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    launchError.value = message
    mcToast.error(message)
  } finally {
    launching.value = false
  }
}

function handleLiveRunUpdate(run: DouyinLeadAcquisitionRunResponse) {
  currentRun.value = normalizeRun(run)
}

function handleRunTerminal(run: DouyinLeadAcquisitionRunResponse) {
  currentRun.value = normalizeRun(run)
  loadRecentRuns()
  loadLeadPool()
  loadLeadStats({ force: true })
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

function isTerminalStatus(status?: string | null): boolean {
  return ['succeeded', 'success', 'completed', 'failed', 'aborted', 'cancelled', 'canceled'].includes(String(status || '').toLowerCase())
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

async function loadRecentRuns() {
  if (recentLoading.value) return
  recentLoading.value = true
  try {
    const response = await leadAcquisitionApi.listDouyinRuns(20)
    recentRuns.value = unwrapApiData<DouyinLeadRunListItem[]>(response, [])
  } catch (error) {
    recentRuns.value = recentRuns.value ?? []
  } finally {
    recentLoading.value = false
  }
}

async function openRunFromHistory(item: DouyinLeadRunListItem) {
  if (!item.runId) return
  await openRunById(item.runId)
}

async function openRunById(runId: string | number | null) {
  if (!runId) return
  try {
    const response = await leadAcquisitionApi.getDouyinRun(runId)
    const run = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    if (!run) throw new Error('未找到任务详情')
    currentRun.value = normalizeRun(run)
    await nextTick()
    document.querySelector('.result-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  }
}

async function loadLeadPool() {
  if (leadPoolLoading.value) return
  leadPoolLoading.value = true
  try {
    const response = await leadAcquisitionApi.listDouyinLeads({
      limit: 50,
      status: leadPoolStatus.value,
      keyword: leadPoolKeyword.value.trim() || undefined,
    })
    leadPool.value = unwrapApiData<DouyinLeadPoolItem[]>(response, [])
  } catch (error) {
    leadPool.value = leadPool.value ?? []
  } finally {
    leadPoolLoading.value = false
  }
}

async function loadLeadStats(options: { force?: boolean } = {}) {
  if (statsLoading.value) {
    if (options.force) statsReloadQueued.value = true
    return
  }
  statsLoading.value = true
  statsError.value = ''
  try {
    const response = await leadAcquisitionApi.getDouyinStats({
      limit: 50,
      keyword: statsKeyword.value.trim() || undefined,
    })
    leadStats.value = unwrapApiData<DouyinLeadStatsResponse>(response, emptyStats())
  } catch (error) {
    statsError.value = '统计读取失败，点击刷新重试'
    leadStats.value = leadStats.value ?? emptyStats()
  } finally {
    statsLoading.value = false
    if (statsReloadQueued.value) {
      statsReloadQueued.value = false
      loadLeadStats()
    }
  }
}

function clearStatsKeyword() {
  statsKeyword.value = ''
  loadLeadStats({ force: true })
}

function emptyStats(): DouyinLeadStatsResponse {
  return {
    taskCount: 0,
    runningTasks: 0,
    succeededTasks: 0,
    failedTasks: 0,
    requestedVideos: 0,
    processedVideos: 0,
    succeededVideos: 0,
    failedVideos: 0,
    commentsCollected: 0,
    matchedComments: 0,
    engagementsCreated: 0,
    sentMessages: 0,
    matchRate: 0,
    engagementRate: 0,
    sendSuccessRate: 0,
    failureReasons: [],
  }
}

function sortLabel(sort?: string | null): string {
  if (sort === 'most_liked') return '最多点赞'
  if (sort === 'latest') return '最新发布'
  return sort || '默认排序'
}

function statusLabel(status?: string | null): string {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'running') return '执行中'
  if (normalized === 'created') return '已创建'
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed') return '成功'
  if (normalized === 'failed') return '失败'
  if (normalized === 'aborted' || normalized === 'cancelled' || normalized === 'canceled') return '已停止'
  if (normalized === 'pending') return '待触达'
  if (normalized === '待触达') return '待触达'
  return status || '-'
}

function reasonLabel(value?: string | null): string {
  const normalized = String(value || '').trim()
  if (!normalized) return ''
  const labels: Record<string, string> = {
    ALL_VIDEOS_FAILED: '所有视频处理失败',
    DM_BUTTON_NOT_FOUND: '未找到私信入口',
    DM_PAGE_NOT_CONFIRMED: '未确认进入私信页',
    ENGAGEMENT_FAILED: '触达执行失败',
    COMMENT_COLLECTION_INCOMPLETE: '评论采集未完整',
    COMMENT_PANEL_LOST_DURING_SCROLL: '滚动时评论区丢失',
    END_OF_LIST_DECLARED_MISMATCH: '评论数量与页面声明不一致',
    RUN_FAILED: '任务执行失败',
    RUN_CANCELLED: '任务已取消',
    UNKNOWN_FAILURE: '未知失败',
    VIDEO_FAILED: '视频处理失败',
  }
  return labels[normalized] ?? normalized
}

function engagementTone(status?: string | null): string {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed' || normalized === 'sent') {
    return 'status-succeeded'
  }
  if (normalized === 'failed') return 'status-danger'
  return 'status-muted'
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 16)
  return date.toLocaleString()
}

function formatPercent(value?: number | null): string {
  const safe = Number.isFinite(Number(value)) ? Number(value) : 0
  return `${(Math.max(0, Math.min(1, safe)) * 100).toFixed(1)}%`
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
    '确认后执行抖音获客，并在结束时汇总每个视频的评论声明数、实际采集数、匹配数，以及每个 engagement 的状态。',
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
.result-panel,
.stats-panel,
.history-panel,
.lead-pool-panel {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
}

.launch-panel,
.result-panel {
  padding: 18px;
}

.result-panel {
  border: 0;
  background: transparent;
  padding: 0;
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

.assist-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.assist-section__head button {
  min-height: 30px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  cursor: pointer;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 700;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head.compact {
  align-items: center;
  margin-bottom: 12px;
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

.template-name-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 12px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.template-name-field input {
  min-height: 34px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.template-name-field input:focus {
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

.template-item--saved {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.template-item--saved > button:first-child {
  min-width: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0;
  text-align: left;
}

.template-delete {
  min-height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-danger, #dc2626);
  cursor: pointer;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 700;
}

.template-delete:hover {
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 10%, transparent);
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

.stats-panel {
  padding: 16px;
}

.stats-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.stats-toolbar label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.stats-toolbar input {
  min-height: 34px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.stats-toolbar input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.stats-toolbar .text-button {
  align-self: end;
  min-height: 34px;
}

.stats-error {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-danger, #dc2626) 32%, var(--mc-border));
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 7%, transparent);
  color: var(--mc-danger, #dc2626);
  font-size: 13px;
}

.stats-card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
}

.metric-card span,
.rate-item span {
  display: block;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.metric-card strong,
.rate-item strong {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-primary);
  font-size: 22px;
  line-height: 1.2;
}

.metric-card small,
.rate-item small {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.stats-insights {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, .72fr);
  gap: 12px;
  margin-top: 12px;
}

.rate-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.rate-item,
.failure-card {
  min-width: 0;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 12px;
}

.failure-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.failure-card__head h3 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 14px;
}

.failure-card__head span {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.failure-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 180px;
  overflow: auto;
  padding-right: 4px;
}

.failure-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px 10px;
  align-items: center;
  color: var(--mc-text-primary);
  font-size: 13px;
}

.failure-row span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-row strong {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.failure-bar {
  grid-column: 1 / -1;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--mc-bg-muted);
}

.failure-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 65%, var(--mc-primary));
}

.empty-inline {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 86px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.growth-workspace {
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr);
  gap: 16px;
}

.history-panel,
.lead-pool-panel {
  min-width: 0;
  padding: 16px;
}

.history-list,
.lead-pool-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  padding-right: 4px;
}

.lead-pool-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 10px;
  margin-bottom: 12px;
}

.lead-pool-filters label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.lead-pool-filters input,
.lead-pool-filters select {
  min-height: 34px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.lead-pool-filters input:focus,
.lead-pool-filters select:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.history-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  width: 100%;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  cursor: pointer;
  padding: 12px;
  text-align: left;
}

.history-item:hover,
.history-item.is-active {
  border-color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 7%, var(--mc-bg));
}

.history-item__main,
.history-item__stats {
  min-width: 0;
}

.history-item__main strong,
.history-item__main small,
.history-item__stats span,
.history-item__stats small {
  display: block;
}

.history-item__main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.history-item__main small,
.history-item__stats small {
  margin-top: 5px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.history-item__stats {
  text-align: right;
}

.history-item__stats span {
  font-size: 13px;
  font-weight: 700;
}

.lead-card {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 12px;
}

.lead-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.lead-card__top a,
.lead-card__top strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--mc-primary);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
}

.lead-card p {
  margin: 8px 0 0;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.lead-card__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 9px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.inline-link {
  min-height: 26px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-primary);
  cursor: pointer;
  padding: 0 4px;
  font-size: 12px;
  font-weight: 700;
}

.inline-link:hover {
  background: color-mix(in srgb, var(--mc-primary) 9%, transparent);
}

.status-pill {
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 700;
}

.status-succeeded {
  background: rgba(22, 163, 74, .12);
  color: #15803d;
}

.status-danger {
  background: rgba(220, 38, 38, .1);
  color: #dc2626;
}

.status-muted {
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}

.empty-card {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 16px;
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
  .result-tables,
  .stats-insights,
  .growth-workspace {
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
  .metric-grid,
  .stats-card-grid,
  .stats-toolbar,
  .rate-strip,
  .lead-pool-filters {
    grid-template-columns: 1fr;
  }

  .assist-section {
    border-right: 0;
    border-bottom: 1px solid var(--mc-border);
  }
}
</style>
