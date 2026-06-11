import { describe, expect, it, vi } from 'vitest'
import { closeTabHandler } from './close_tab'

describe('closeTabHandler', () => {
  it('removes the resolved tab id', async () => {
    const remove = vi.fn(async () => undefined)
    const handler = closeTabHandler({
      chrome: { tabs: { remove } } as unknown as typeof chrome,
      clock: () => 1000,
    })

    const result = await handler(42, {}, 1000)

    expect(remove).toHaveBeenCalledExactlyOnceWith(42)
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual({ tabId: 42 })
    }
  })

  it('surfaces tab removal failures as NO_TARGET_TAB', async () => {
    const remove = vi.fn(async () => {
      throw new Error('No tab with id: 42')
    })
    const handler = closeTabHandler({
      chrome: { tabs: { remove } } as unknown as typeof chrome,
    })

    const result = await handler(42, {}, 1000).catch(err => err)

    expect(result.code).toBe('NO_TARGET_TAB')
    expect(result.retryable).toBe(false)
  })
})
