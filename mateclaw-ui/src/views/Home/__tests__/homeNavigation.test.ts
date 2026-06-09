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
    expect(layout).not.toContain("path: '/dashboard',\n        label: t('nav.dashboard'")
  })

  it('sends successful logins to Home when chat access is available', () => {
    expect(login).toContain("workspaceStore.can('chat') ? '/home' : '/chat'")
    expect(login).not.toContain("workspaceStore.can('view:dashboard') ? '/dashboard' : '/chat'")
  })

  it('defines localized home labels', () => {
    expect(zh).toContain("home: '首页'")
    expect(en).toContain("home: 'Home'")
  })
})
