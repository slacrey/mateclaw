import { describe, expect, it } from 'vitest'
import leadPage from '../LeadAcquisition/index.vue?raw'
import api from '../../api/index.ts?raw'
import livePanel from '../../components/lead/LeadRunLivePanel.vue?raw'

describe('lead acquisition realtime execution contract', () => {
  const combined = [leadPage, api, livePanel].join('\n')

  it('mounts a dedicated live run panel after starting acquisition', () => {
    expect(leadPage).toContain('LeadRunLivePanel')
    expect(leadPage).toContain('leadAcquisitionApi.startDouyinRun(payload)')
  })

  it('has an SSE client for run event streaming with resume support', () => {
    expect(combined).toContain('EventSource')
    expect(combined).toContain('/lead-acquisition/runs/')
    expect(combined).toContain('/events/stream')
    expect(combined).toContain('afterEventId')
  })

  it('falls back to polling and tells the user when realtime reconnects', () => {
    expect(combined).toContain('leadAcquisitionApi.getDouyinRun')
    expect(combined).toContain('实时连接恢复中')
    expect(combined).toContain('setInterval')
  })

  it('renders the live events needed by the execution timeline', () => {
    expect(combined).toContain('lead.video.started')
    expect(combined).toContain('lead.comments.collected')
    expect(combined).toContain('lead.comment.matched')
    expect(combined).toContain('lead.engagement.completed')
  })
})
