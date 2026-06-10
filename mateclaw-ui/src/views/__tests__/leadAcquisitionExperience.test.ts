import { describe, expect, it } from 'vitest'
import leadPage from '../LeadAcquisition/index.vue?raw'
import livePanel from '../../components/lead/LeadRunLivePanel.vue?raw'
import runResult from '../../components/lead/DouyinLeadRunResult.vue?raw'
import api from '../../api/index.ts?raw'
import chatConsole from '../ChatConsole.vue?raw'
import router from '../../router/index.ts?raw'
import settingsLayout from '../Settings/Layout.vue?raw'

describe('lead acquisition experience', () => {
  it('offers a business-first Douyin lead workflow page', () => {
    expect(leadPage).toContain('获客专家')
    expect(leadPage).toContain('抖音获客')
    expect(leadPage).toContain('leadAcquisitionApi.startDouyinRun(payload)')
    expect(leadPage).toContain('常用模板')
    expect(leadPage).toContain('执行环境')
    expect(leadPage).toContain('<BrowserPairingPanel embedded compact />')
    expect(leadPage).toContain('const browserPanelOpen = ref(false)')
    expect(leadPage).toContain('v-if="browserPanelOpen"')
    expect(leadPage).toContain('function toggleBrowserPanel')
    expect(leadPage).toContain('function focusBrowserPanel')
    expect(leadPage).toContain('<LeadRunLivePanel')
    expect(leadPage).toContain('@update:run="handleLiveRunUpdate"')
    expect(leadPage).toContain("mcToast.success('抖音获客任务已启动')")
    expect(leadPage).not.toContain("router.push('/settings/browser')")
  })

  it('starts Douyin acquisition into a realtime run panel', () => {
    expect(leadPage).toContain('const activeRunRunning = computed')
    expect(leadPage).toContain('const taskLocked = computed')
    expect(leadPage).toContain('const launchButtonText = computed')
    expect(leadPage).toContain('currentRun.value = normalizeRun(run)')
    expect(leadPage).toContain('LeadRunLivePanel')
  })

  it('exposes the Douyin run event stream API contract', () => {
    expect(api).toContain('streamDouyinRunEventsUrl')
    expect(api).toContain('/lead-acquisition/runs/${runId}/events/stream')
    expect(api).toContain("params.set('token', token)")
    expect(api).toContain("params.set('afterEventId', String(afterEventId))")
  })

  it('renders realtime progress with SSE fallback and final summary', () => {
    expect(livePanel).toContain('new EventSource(url)')
    expect(livePanel).toContain('lead.comments.collecting')
    expect(livePanel).toContain('lead.engagement.completed')
    expect(livePanel).toContain('查看完整时间线')
    expect(livePanel).toContain('timeline-scroll')
    expect(livePanel).toContain('搜索与排序')
    expect(livePanel).toContain('线索触达')
    expect(livePanel).toContain('实时连接恢复中，已切换为 2 秒刷新一次。')
    expect(livePanel).toContain('window.setInterval')
    expect(livePanel).toContain('leadAcquisitionApi.getDouyinRun')
    expect(livePanel).toContain('最终汇总')
    expect(livePanel).toContain('技术明细')
    expect(runResult).toContain('展开评论明细')
    expect(runResult).toContain('panel-scroll-body')
  })

  it('retires the old browser settings entry after moving pairing into acquisition', () => {
    expect(settingsLayout).not.toContain("path: '/settings/browser'")
    expect(settingsLayout).not.toContain("id: 'browser'")
    expect(router).not.toContain("name: 'SettingsBrowser'")
    expect(router).toContain("{ path: 'settings/browser', redirect: '/lead-acquisition' }")
  })

  it('lets the lead expert chat surface start from a Douyin lead prompt', () => {
    expect(chatConsole).toContain('function isLeadExpertAgent')
    expect(chatConsole).toContain('function douyinLeadStarterPrompt')
    expect(chatConsole).toContain('确认后执行抖音获客，并在结束时汇总')
    expect(chatConsole).toContain('pendingRoutePrompt')
  })
})
