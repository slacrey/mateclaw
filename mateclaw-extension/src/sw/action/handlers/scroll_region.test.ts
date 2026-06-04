import { describe, expect, it, vi } from 'vitest'
import { RegionRegistry } from '../../../runtime/region-registry'
import { scrollRegionHandler } from './scroll_region'
import type { ActionResult } from '../types'

function ok(payload: Record<string, unknown> = {}): ActionResult {
  return { ok: true, elapsed_ms: 0, payload }
}

describe('scroll_region handler', () => {
  it('uses the registered region center and delegates to the compatible scroll handler', async () => {
    const regions = new RegionRegistry()
    regions.register({
      key: 'feed',
      tabId: 42,
      rect: { x: 10, y: 20, width: 200, height: 300 },
    })
    const scroll = vi.fn(async () => ok({}))
    const handler = scrollRegionHandler({ regions, scroll })

    const result = await handler(42, {
      regionKey: 'feed',
      direction: 'down',
      amount: 500,
      stopWhen: { type: 'edge' },
      segments: 5,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(scroll).toHaveBeenCalledExactlyOnceWith(42, {
      direction: 'down',
      distance_px: 500,
      segments: 5,
      x: 110,
      y: 170,
    }, 1000)
  })

  it('fails with a typed error when the region is missing', async () => {
    const handler = scrollRegionHandler({
      regions: new RegionRegistry(),
      scroll: vi.fn(async () => ok({})),
    })

    await expect(handler(42, {
      regionKey: 'missing',
      direction: 'down',
      amount: 500,
    }, 1000)).rejects.toMatchObject({
      code: 'GROUNDING_AMBIGUOUS',
      retryable: true,
    })
  })
})
