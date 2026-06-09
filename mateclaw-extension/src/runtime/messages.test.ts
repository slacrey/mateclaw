import { describe, expect, it } from 'vitest'
import { parseRegionClearMessage, parseRegionRegistrationMessage } from './messages'

describe('Browser Runtime v2 region messages', () => {
  it('parses region registration messages', () => {
    expect(parseRegionRegistrationMessage({
      kind: 'runtime.region.register',
      payload: {
        regionKey: 'feed',
        tabId: 9,
        rect: { x: 1, y: 2, width: 300, height: 400 },
        source: 'som',
      },
    })).toEqual({
      key: 'feed',
      tabId: 9,
      rect: { x: 1, y: 2, width: 300, height: 400 },
      source: 'som',
    })
  })

  it('rejects invalid region registrations', () => {
    expect(parseRegionRegistrationMessage({
      kind: 'runtime.region.register',
      payload: { regionKey: 'feed', tabId: 9, rect: { x: 1, y: 2, width: 0, height: 400 } },
    })).toBeNull()
  })

  it('parses clear messages', () => {
    expect(parseRegionClearMessage({
      kind: 'runtime.region.clear',
      payload: { tabId: 9, regionKey: 'feed' },
    })).toEqual({ tabId: 9, regionKey: 'feed' })
  })
})
