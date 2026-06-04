import { describe, expect, it } from 'vitest'
import { RegionRegistry } from './region-registry'
import { parseScrollRegionAction, parseScrollRegionParams, toCompatibleScrollParams } from './scroll-region'

describe('Browser Runtime v2 scroll_region parsing', () => {
  it('accepts valid scroll_region payload with stopWhen', () => {
    const parsed = parseScrollRegionAction({
      kind: 'scroll_region',
      params: {
        regionKey: 'main-feed',
        direction: 'down',
        amount: 480,
        stopWhen: { type: 'selector_visible', selector: '#done' },
      },
    })

    expect(parsed?.params).toMatchObject({
      regionKey: 'main-feed',
      direction: 'down',
      amount: 480,
      stopWhen: { type: 'selector_visible', selector: '#done' },
    })
  })

  it('rejects malformed payloads', () => {
    expect(parseScrollRegionParams({ regionKey: '', direction: 'down', amount: 100 })).toBeNull()
    expect(parseScrollRegionParams({ regionKey: 'r', direction: 'diagonal', amount: 100 })).toBeNull()
    expect(parseScrollRegionParams({ regionKey: 'r', direction: 'down', amount: 0 })).toBeNull()
    expect(parseScrollRegionParams({
      regionKey: 'r',
      direction: 'down',
      amount: 100,
      stopWhen: { type: 'selector_visible' },
    })).toBeNull()
  })

  it('maps a region to compatible scroll params centered on the region', () => {
    const registry = new RegionRegistry()
    const region = registry.register({
      key: 'article',
      tabId: 42,
      rect: { x: 20, y: 40, width: 300, height: 500 },
    }, 123)

    expect(registry.get(42, 'article')).toEqual(region)
    expect(toCompatibleScrollParams({
      regionKey: 'article',
      direction: 'down',
      amount: 240,
      segments: 3,
    }, region)).toEqual({
      direction: 'down',
      distance_px: 240,
      segments: 3,
      x: 170,
      y: 290,
    })
  })
})
