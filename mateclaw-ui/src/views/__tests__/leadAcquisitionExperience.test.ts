import { describe, expect, it } from 'vitest'
import leadPage from '../LeadAcquisition/index.vue?raw'
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
    expect(leadPage).toContain('执行汇总')
    expect(leadPage).not.toContain("router.push('/settings/browser')")
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
