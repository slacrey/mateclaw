import { describe, expect, it } from 'vitest'
import home from '../index.vue?raw'

describe('home page contract', () => {
  it('loads recommended employees and recent scheduled runs from existing APIs', () => {
    expect(home).toContain("agentApi.list({ enabled: true })")
    expect(home).toContain('dashboardApi.recentRuns(8)')
  })

  it('renders conditional demo video and environment prompts', () => {
    expect(home).toContain('demoVideoUrl')
    expect(home).toContain('showVideoModal')
    expect(home).toContain('isClientEnvironment')
    expect(home).toContain('browserPluginDownloadUrl')
    expect(home).toContain('clientDownloadUrl')
  })

  it('starts a new chat with the selected employee', () => {
    expect(home).toContain("path: '/chat'")
    expect(home).toContain("action: 'newChat'")
    expect(home).toContain('agentId')
  })
})
