import { describe, expect, it } from 'vitest'
import leadPage from '../LeadAcquisition/index.vue?raw'
import chatConsole from '../ChatConsole.vue?raw'

describe('lead acquisition experience', () => {
  it('offers a business-first Douyin lead workflow page', () => {
    expect(leadPage).toContain('获客专家')
    expect(leadPage).toContain('抖音获客')
    expect(leadPage).toContain('leadAcquisitionApi.startDouyinRun(payload)')
    expect(leadPage).toContain('常用模板')
    expect(leadPage).toContain('执行环境')
    expect(leadPage).toContain('执行汇总')
  })

  it('lets the lead expert chat surface start from a Douyin lead prompt', () => {
    expect(chatConsole).toContain('function isLeadExpertAgent')
    expect(chatConsole).toContain('function douyinLeadStarterPrompt')
    expect(chatConsole).toContain('抖音获客 V2')
    expect(chatConsole).toContain('pendingRoutePrompt')
  })
})
