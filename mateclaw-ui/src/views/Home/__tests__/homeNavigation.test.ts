import { describe, expect, it } from 'vitest'
import router from '../../../router/index.ts?raw'
import layout from '../../layout/MainLayout.vue?raw'
import login from '../../Login.vue?raw'
import zh from '../../../i18n/locales/zh-CN.ts?raw'
import en from '../../../i18n/locales/en-US.ts?raw'

describe('home navigation', () => {
  it('registers home as the authenticated default route', () => {
    expect(router).toContain("redirect: '/home'")
    expect(router).toContain("path: 'home'")
    expect(router).toContain("name: 'Home'")
    expect(router).toContain("component: () => import('@/views/Home/index.vue')")
  })

  it('shows Home in the sidebar and hides Dashboard from the visible menu', () => {
    expect(layout).toContain("path: '/home'")
    expect(layout).toContain("t('nav.home'")
    expect(layout).toContain("path: '/lead-acquisition'")
    expect(layout).toContain("t('nav.leadAcquisition'")
    expect(layout).not.toContain("path: '/dashboard',\n        label: t('nav.dashboard'")
  })

  it('registers lead acquisition as a business route for chat-capable users', () => {
    expect(router).toContain("path: 'lead-acquisition'")
    expect(router).toContain("name: 'LeadAcquisition'")
    expect(router).toContain("component: () => import('@/views/LeadAcquisition/index.vue')")
    expect(router).toContain("meta: { title: 'Lead Acquisition', requiredCapability: 'chat' }")
    expect(router).toContain("name: 'DouyinLeadRunDetail'")
    expect(router).toContain("meta: { title: 'Douyin Lead Run', requiredCapability: 'chat' }")
  })

  it('sends successful logins to Home when chat access is available', () => {
    expect(login).toContain("workspaceStore.can('chat') ? '/home' : '/chat'")
    expect(login).not.toContain("workspaceStore.can('view:dashboard') ? '/dashboard' : '/chat'")
  })

  it('defines localized home labels', () => {
    expect(zh).toContain("home: '首页'")
    expect(zh).toContain("leadAcquisition: '获客'")
    expect(en).toContain("home: 'Home'")
    expect(en).toContain("leadAcquisition: 'Leads'")
  })
})
