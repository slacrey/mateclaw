<template>
  <div class="mc-page-shell dashboard-shell">
    <div class="mc-page-frame dashboard-frame">
      <div class="mc-page-inner dashboard-inner">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('dashboard.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('dashboard.title') }}</h1>
            <p class="mc-page-desc">{{ t('dashboard.desc') }}</p>
          </div>
          <div class="hero-note mc-surface-card">
            <div class="hero-note__label">{{ t('dashboard.periods.today') }}</div>
            <div class="hero-note__value">{{ todayStats.conversations }}</div>
            <div class="hero-note__meta">{{ t('dashboard.conversations') }} · {{ todayStats.messages }} {{ t('dashboard.messages') }}</div>
          </div>
        </div>

        <div class="dashboard-body">
          <div class="stats-grid">
            <div class="stat-card mc-surface-card stat-card--primary">
              <div class="stat-icon">
                <el-icon><ChatDotRound /></el-icon>
              </div>
              <div class="stat-body">
                <div class="stat-value">{{ todayStats.conversations }}</div>
                <div class="stat-label">{{ t('dashboard.conversations') }}</div>
              </div>
            </div>
            <div class="stat-card mc-surface-card stat-card--primary">
              <div class="stat-icon">
                <el-icon><Document /></el-icon>
              </div>
              <div class="stat-body">
                <div class="stat-value">{{ todayStats.messages }}</div>
                <div class="stat-label">{{ t('dashboard.messages') }}</div>
              </div>
            </div>
            <div class="stat-card mc-surface-card stat-card--secondary">
              <div class="stat-icon">
                <el-icon><Tools /></el-icon>
              </div>
              <div class="stat-body">
                <div class="stat-value">{{ todayStats.toolCalls }}</div>
                <div class="stat-label">{{ t('dashboard.toolCalls') }}</div>
              </div>
            </div>
            <div class="stat-card mc-surface-card stat-card--secondary">
              <div class="stat-icon">
                <el-icon><DataLine /></el-icon>
              </div>
              <div class="stat-body">
                <div class="stat-value">{{ formatTokens(todayStats.totalTokens) }}</div>
                <div class="stat-label">{{ t('dashboard.tokens') }}</div>
              </div>
            </div>
          </div>

          <div class="models-section">
            <div class="section-head">
              <h2 class="section-title">{{ t('dashboard.models.title') }}</h2>
              <p class="section-subtitle">{{ t('dashboard.models.subtitle') }}</p>
            </div>
            <div class="models-card mc-surface-card">
              <div class="models-card__head">
                <div class="active-model">
                  <span class="active-model__label">{{ t('dashboard.models.activeModel') }}</span>
                  <span v-if="activeModel" class="active-model__value">
                    <span class="active-model__dot"></span>
                    {{ activeProviderLabel }} · {{ activeModel.model }}
                  </span>
                  <span v-else class="active-model__value active-model__value--empty">
                    {{ t('dashboard.models.notSet') }}
                  </span>
                </div>
                <div class="models-card__actions">
                  <span v-if="modelProviders.length" class="models-count">
                    {{ readyProviderCount }}/{{ modelProviders.length }} {{ t('dashboard.models.configured') }}
                  </span>
                  <button class="models-manage" @click="goToModels">
                    {{ t('dashboard.models.manage') }}
                    <el-icon><ArrowRight /></el-icon>
                  </button>
                </div>
              </div>
              <div v-if="modelProviders.length" class="provider-chips">
                <button
                  v-for="p in modelProviders"
                  :key="p.id"
                  class="provider-chip"
                  :class="'provider-chip--' + providerChipStatus(p)"
                  :title="p.name"
                  @click="goToModels"
                >
                  <span class="provider-chip__dot"></span>
                  <img
                    class="provider-chip__icon"
                    :src="getProviderIcon(p.id)"
                    :alt="p.name"
                    @error="onProviderIconError"
                  />
                  <span class="provider-chip__name">{{ p.name }}</span>
                </button>
              </div>
              <div v-else class="models-empty">
                <span class="models-empty__text">{{ t('dashboard.models.empty') }}</span>
                <button class="models-empty__btn" @click="goToModels">{{ t('dashboard.models.emptyCta') }}</button>
              </div>
            </div>
          </div>

          <div v-if="trendData.length" class="trend-section">
            <div class="section-head">
              <h2 class="section-title">{{ t('dashboard.trend.title', '7-Day Trend') }}</h2>
              <p class="section-subtitle">{{ t('dashboard.trend.subtitle', 'Messages and token consumption over the past week.') }}</p>
            </div>
            <div class="trend-chart mc-surface-card">
              <div ref="chartRef" class="chart-container"></div>
            </div>
          </div>

          <div class="comparison-section">
            <div class="section-head">
              <h2 class="section-title">{{ t('dashboard.periodComparison') }}</h2>
              <p class="section-subtitle">{{ t('dashboard.periodDesc') }}</p>
            </div>
            <div class="comparison-grid">
              <div class="comparison-card mc-surface-card" v-for="(period, key) in overview" :key="key">
                <h3 class="comparison-title">{{ t('dashboard.periods.' + key) }}</h3>
                <div class="comparison-row">
                  <span class="comparison-label">{{ t('dashboard.conversations') }}</span>
                  <span class="comparison-value">{{ period.conversations }}</span>
                </div>
                <div class="comparison-row">
                  <span class="comparison-label">{{ t('dashboard.messages') }}</span>
                  <span class="comparison-value">{{ period.messages }}</span>
                </div>
                <div class="comparison-row">
                  <span class="comparison-label">{{ t('dashboard.tokens') }}</span>
                  <span class="comparison-value">{{ formatTokens(period.totalTokens) }}</span>
                </div>
                <div class="comparison-row">
                  <span class="comparison-label">{{ t('dashboard.toolCalls') }}</span>
                  <span class="comparison-value">{{ period.toolCalls }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="runs-section">
            <div class="section-head">
              <h2 class="section-title">{{ t('dashboard.recentRuns') }}</h2>
              <p class="section-subtitle">{{ t('dashboard.runsDesc') }}</p>
            </div>
            <div class="runs-table-wrapper mc-surface-card">
              <table v-if="recentRuns.length" class="runs-table">
                <thead>
                  <tr>
                    <th>{{ t('dashboard.runColumns.time') }}</th>
                    <th>{{ t('dashboard.runColumns.job') }}</th>
                    <th>{{ t('dashboard.runColumns.status') }}</th>
                    <th>{{ t('dashboard.runColumns.trigger') }}</th>
                    <th>{{ t('dashboard.runColumns.duration') }}</th>
                    <th>{{ t('dashboard.runColumns.tokens') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="run in recentRuns" :key="run.id">
                    <td class="cell-time">{{ formatTime(run.startedAt) }}</td>
                    <td class="cell-job">#{{ run.cronJobId }}</td>
                    <td>
                      <span class="status-badge" :class="'status-' + run.status">{{ run.status }}</span>
                    </td>
                    <td class="cell-trigger">{{ run.triggerType }}</td>
                    <td class="cell-duration">{{ calcDuration(run) }}</td>
                    <td class="cell-tokens">{{ run.tokenUsage || '-' }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="empty-state">{{ t('dashboard.noRuns') }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ArrowRight, ChatDotRound, DataLine, Document, Tools } from '@element-plus/icons-vue'
import { dashboardApi, modelApi } from '@/api'
import { getProviderIcon, onProviderIconError } from '@/utils/providerIcons'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const { t, locale } = useI18n()
const router = useRouter()

const overview = ref<Record<string, any>>({})
const recentRuns = ref<any[]>([])
const trendData = ref<any[]>([])
const chartRef = ref<HTMLElement | null>(null)
let chartInstance: echarts.ECharts | null = null

const todayStats = reactive({
  conversations: 0,
  messages: 0,
  totalTokens: 0,
  toolCalls: 0,
  errors: 0,
})

// ── Model configuration card ──
const modelProviders = ref<any[]>([])
const activeModel = ref<{ providerId: string; model: string } | null>(null)

const readyProviderCount = computed(
  () => modelProviders.value.filter((p) => providerChipStatus(p) === 'ready').length,
)

const activeProviderLabel = computed(() => {
  if (!activeModel.value) return ''
  const match = modelProviders.value.find((p) => p.id === activeModel.value!.providerId)
  return match?.name || activeModel.value.providerId
})

// Maps a provider's liveness to a chip status, falling back to the legacy
// configured/available booleans for backends that don't report liveness.
function providerChipStatus(p: any): 'ready' | 'partial' | 'down' {
  switch (p.liveness) {
    case 'LIVE':
      return 'ready'
    case 'COOLDOWN':
    case 'UNPROBED':
      return 'partial'
    case 'REMOVED':
    case 'UNCONFIGURED':
      return 'down'
  }
  if (p.available) return 'ready'
  if (p.configured || (p.models?.length || 0) + (p.extraModels?.length || 0) > 0) return 'partial'
  return 'down'
}

function goToModels() {
  router.push('/settings/models')
}

onMounted(async () => {
  try {
    const [overviewRes, runsRes, trendRes] = await Promise.all([
      dashboardApi.overview(),
      dashboardApi.recentRuns(10),
      dashboardApi.trend(7),
    ])
    overview.value = (overviewRes as any).data || {}
    const today = overview.value.today || {}
    Object.assign(todayStats, today)
    recentRuns.value = (runsRes as any).data || []
    trendData.value = (trendRes as any).data || []
    if (trendData.value.length) {
      await nextTick()
      renderChart()
    }
  } catch {
    // Dashboard data is non-critical
  }

  // Model configuration card — loaded independently so a failure here never
  // blanks the analytics above, and vice versa.
  try {
    const [provRes, activeRes] = await Promise.all([
      modelApi.listProviders().catch(() => ({ data: [] })),
      modelApi.getActive().catch(() => ({ data: null })),
    ])
    modelProviders.value = (provRes as any).data || []
    activeModel.value = (activeRes as any).data?.activeLlm || null
  } catch {
    // Non-critical
  }
})

onUnmounted(() => {
  chartInstance?.dispose()
})

function renderChart() {
  if (!chartRef.value) return
  chartInstance = echarts.init(chartRef.value)

  const dates = trendData.value.map((d: any) => d.date?.slice(5) || '') // MM-DD
  const messages = trendData.value.map((d: any) => d.messages || 0)
  const tokens = trendData.value.map((d: any) => d.totalTokens || 0)

  const style = getComputedStyle(document.documentElement)
  const textColor = style.getPropertyValue('--mc-text-secondary').trim() || '#415276'
  const borderColor = style.getPropertyValue('--mc-border-light').trim() || 'rgba(162, 187, 242, 0.34)'
  const primaryColor = style.getPropertyValue('--mc-primary').trim() || '#476CFF'
  const accentColor = style.getPropertyValue('--mc-accent').trim() || '#19BFD1'

  chartInstance.setOption({
    tooltip: { trigger: 'axis' },
    legend: {
      data: [t('dashboard.messages'), 'Tokens'],
      textStyle: { color: textColor, fontSize: 12 },
      bottom: 0,
    },
    grid: { top: 10, right: 16, bottom: 36, left: 48, containLabel: false },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { color: textColor, fontSize: 11 },
      axisLine: { lineStyle: { color: borderColor } },
    },
    yAxis: [
      {
        type: 'value',
        axisLabel: { color: textColor, fontSize: 11 },
        splitLine: { lineStyle: { color: borderColor, type: 'dashed' } },
      },
      {
        type: 'value',
        axisLabel: { color: textColor, fontSize: 11, formatter: (v: number) => v >= 1000 ? (v / 1000).toFixed(0) + 'K' : v },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: t('dashboard.messages'),
        type: 'line',
        data: messages,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5, color: primaryColor },
        itemStyle: { color: primaryColor },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: colorWithAlpha(primaryColor, 0.22) },
          { offset: 1, color: colorWithAlpha(primaryColor, 0.03) },
        ])},
      },
      {
        name: 'Tokens',
        type: 'line',
        yAxisIndex: 1,
        data: tokens,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2, color: accentColor },
        itemStyle: { color: accentColor },
      },
    ],
  })

  // Responsive resize
  const ro = new ResizeObserver(() => chartInstance?.resize())
  ro.observe(chartRef.value!)
}

function colorWithAlpha(color: string, alpha: number): string {
  const value = color.trim()
  const hex = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i)
  if (hex) {
    const raw = hex[1].length === 3
      ? hex[1].split('').map((char) => char + char).join('')
      : hex[1]
    const r = Number.parseInt(raw.slice(0, 2), 16)
    const g = Number.parseInt(raw.slice(2, 4), 16)
    const b = Number.parseInt(raw.slice(4, 6), 16)
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
  }

  const rgb = value.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i)
  if (rgb) {
    return `rgba(${rgb[1]}, ${rgb[2]}, ${rgb[3]}, ${alpha})`
  }

  return `rgba(71, 108, 255, ${alpha})`
}

watch(locale, () => {
  if (trendData.value.length) renderChart()
})

function formatTokens(n: number): string {
  if (!n) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}

function formatTime(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString()
}

function calcDuration(run: any): string {
  if (!run.startedAt || !run.finishedAt) return '-'
  const ms = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(1) + 's'
}
</script>

<style scoped>
.dashboard-shell {
  --dashboard-card-hover-bg: rgba(71, 108, 255, 0.07);
  --dashboard-card-shadow: 0 16px 36px rgba(45, 83, 180, 0.14);
  --dashboard-focus-ring: rgba(71, 108, 255, 0.22);
  --dashboard-cyan-soft: rgba(25, 191, 209, 0.12);
  --dashboard-cyan-border: rgba(25, 191, 209, 0.28);

  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

html.dark .dashboard-shell {
  --dashboard-card-hover-bg: rgba(142, 166, 255, 0.12);
  --dashboard-card-shadow: 0 18px 42px rgba(0, 8, 32, 0.36);
  --dashboard-focus-ring: rgba(142, 166, 255, 0.28);
  --dashboard-cyan-soft: rgba(25, 191, 209, 0.16);
  --dashboard-cyan-border: rgba(25, 191, 209, 0.34);
}

.dashboard-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.dashboard-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.dashboard-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.hero-note {
  min-width: 220px;
  padding: 16px 18px;
  border-color: var(--mc-border);
}

.hero-note__label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--mc-accent);
  margin-bottom: 10px;
}

.hero-note__value {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.05em;
  color: var(--mc-text-primary);
}

.hero-note__meta {
  margin-top: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.section-head {
  margin-bottom: 12px;
}

.section-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--mc-text-primary);
  letter-spacing: -0.03em;
  margin: 0 0 4px;
}

.section-subtitle {
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
.stat-card {
  display: flex; align-items: center; gap: 14px;
  padding: 18px;
  border-color: var(--mc-border-light);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}
.stat-card:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--dashboard-card-shadow);
  transform: translateY(-1px);
}
.stat-icon {
  width: 52px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  border: 1px solid var(--mc-border-light);
  background: linear-gradient(135deg, var(--mc-primary-bg), var(--dashboard-cyan-soft));
  font-size: 24px;
  color: var(--mc-primary);
}
.stat-body { display: flex; flex-direction: column; }
.stat-value { font-size: 30px; font-weight: 800; color: var(--mc-text-primary); line-height: 1; letter-spacing: -0.05em; }
.stat-label { font-size: 12px; color: var(--mc-text-tertiary); margin-top: 6px; text-transform: uppercase; letter-spacing: 0.08em; }

.stat-card--secondary .stat-icon { background: linear-gradient(135deg, var(--dashboard-cyan-soft), rgba(71, 108, 255, 0.08)); color: var(--mc-accent); border-color: var(--dashboard-cyan-border); }
.stat-card--secondary .stat-value { font-size: 24px; }

/* Model Configuration card */
.models-section { margin-bottom: 22px; }
.models-card {
  padding: 18px;
  border-color: var(--mc-border);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.models-card:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--dashboard-card-shadow);
}
.models-card__head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 16px; flex-wrap: wrap;
}
.active-model { display: flex; align-items: center; gap: 10px; min-width: 0; }
.active-model__label {
  font-size: 12px; color: var(--mc-text-tertiary);
  text-transform: uppercase; letter-spacing: 0.08em;
}
.active-model__value {
  display: flex; align-items: center; gap: 7px;
  font-size: 14px; font-weight: 700; color: var(--mc-text-primary);
}
.active-model__value--empty { color: var(--mc-text-tertiary); font-weight: 600; }
.active-model__dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--mc-success); flex-shrink: 0;
  box-shadow: 0 0 0 3px var(--mc-success-bg);
}
.models-card__actions { display: flex; align-items: center; gap: 14px; }
.models-count { font-size: 12px; color: var(--mc-text-tertiary); white-space: nowrap; }
.models-manage {
  display: flex; align-items: center; gap: 3px;
  font-size: 13px; font-weight: 600; color: var(--mc-primary);
  background: none; border: none; cursor: pointer;
  padding: 4px 6px; border-radius: 6px; transition: background 0.15s ease, box-shadow 0.15s ease, color 0.15s ease;
}
.models-manage:hover {
  background: var(--mc-primary-bg);
  color: var(--mc-primary-hover);
}
.models-manage:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--dashboard-focus-ring);
}

.provider-chips {
  display: flex; flex-wrap: wrap; gap: 8px;
  margin-top: 16px; padding-top: 16px;
  border-top: 1px solid var(--mc-border-light);
}
.provider-chip {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 11px; border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  font-size: 12px; font-weight: 600; color: var(--mc-text-secondary);
  cursor: pointer; transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease, color 0.15s ease, transform 0.15s ease;
}
.provider-chip:hover {
  border-color: var(--mc-border-strong);
  background: var(--dashboard-card-hover-bg);
  box-shadow: 0 8px 18px rgba(45, 83, 180, 0.10);
  color: var(--mc-text-primary);
  transform: translateY(-1px);
}
.provider-chip:focus-visible {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px var(--dashboard-focus-ring);
}
.provider-chip__dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.provider-chip__icon {
  width: 15px; height: 15px; border-radius: 4px;
  object-fit: contain; flex-shrink: 0;
}
.provider-chip--ready { border-color: var(--mc-success-border); }
.provider-chip--ready .provider-chip__dot { background: var(--mc-success); }
.provider-chip--partial { border-color: var(--dashboard-cyan-border); }
.provider-chip--partial .provider-chip__dot { background: var(--mc-accent); }
.provider-chip--down .provider-chip__dot { background: var(--mc-text-tertiary); }
.provider-chip--down { opacity: 0.7; }

.models-empty {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-top: 16px; padding-top: 16px;
  border-top: 1px solid var(--mc-border-light);
}
.models-empty__text { font-size: 13px; color: var(--mc-text-tertiary); }
.models-empty__btn {
  padding: 6px 14px; border-radius: 8px;
  background: var(--mc-primary); color: #fff;
  border: none; font-size: 12px; font-weight: 600; cursor: pointer;
  transition: background 0.15s ease, box-shadow 0.15s ease;
}
.models-empty__btn:hover {
  background: var(--mc-primary-hover);
}
.models-empty__btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--dashboard-focus-ring);
}

.trend-section { margin-bottom: 22px; }
.trend-chart {
  padding: 18px;
  border-color: var(--mc-border);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.trend-chart:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--dashboard-card-shadow);
}
.chart-container { width: 100%; height: 240px; }

.comparison-section { margin-bottom: 22px; }
.comparison-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.comparison-card {
  padding: 16px 16px 10px;
  border-color: var(--mc-border-light);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}
.comparison-card:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--dashboard-card-shadow);
  transform: translateY(-1px);
}
.comparison-title { font-size: 12px; font-weight: 700; color: var(--mc-accent); margin: 0 0 12px; text-transform: uppercase; letter-spacing: 0.09em; }
.comparison-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid var(--mc-border-light); }
.comparison-row:last-child { border-bottom: none; }
.comparison-label { font-size: 13px; color: var(--mc-text-tertiary); }
.comparison-value { font-size: 14px; font-weight: 700; color: var(--mc-text-primary); }

/* Runs Section */
.runs-section { min-height: 0; }
.runs-table-wrapper {
  overflow: auto;
  max-height: 100%;
  border-color: var(--mc-border);
}
.runs-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.runs-table th {
  padding: 9px 14px; text-align: left; font-weight: 600; font-size: 12px;
  color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.03em;
  background: var(--mc-bg-muted); border-bottom: 1px solid var(--mc-border-light);
}
.runs-table td { padding: 9px 14px; border-bottom: 1px solid var(--mc-border-light); color: var(--mc-text-primary); }
.runs-table tr:last-child td { border-bottom: none; }
.runs-table tbody tr { transition: background 0.15s ease; }
.runs-table tbody tr:hover { background: var(--dashboard-card-hover-bg); }

.cell-time { font-size: 12px; color: var(--mc-text-tertiary); white-space: nowrap; }
.cell-job { font-family: 'SF Mono', monospace; font-size: 12px; color: var(--mc-text-secondary); }
.cell-trigger { font-size: 12px; color: var(--mc-text-tertiary); }
.cell-duration { font-family: 'SF Mono', monospace; font-size: 12px; }
.cell-tokens { font-family: 'SF Mono', monospace; font-size: 12px; }

.status-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.status-running { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-completed { background: var(--mc-success-bg); color: var(--mc-success); }
.status-failed { background: var(--mc-danger-bg); color: var(--mc-danger); }

.empty-state { padding: 48px; text-align: center; color: var(--mc-text-tertiary); font-size: 14px; }

@media (max-width: 768px) {
  .dashboard-frame {
    height: 100%;
    min-height: calc(100vh - 28px);
  }

  .dashboard-body {
    overflow: visible;
    padding-right: 0;
  }

  .hero-note {
    width: 100%;
    min-width: 0;
  }
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .comparison-grid { grid-template-columns: 1fr; }
}

@media (max-width: 480px) {
  .stats-grid { grid-template-columns: 1fr; }
  .stat-card { min-width: 0; }
  .stat-value { font-size: 28px; }
}
</style>
