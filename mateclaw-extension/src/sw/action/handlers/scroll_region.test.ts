import { describe, expect, it, vi } from 'vitest'
import { RegionRegistry } from '../../../runtime/region-registry'
import { scrollRegionHandler } from './scroll_region'
import type { ActionResult } from '../types'

function ok(payload: Record<string, unknown> = {}): ActionResult {
  return { ok: true, elapsed_ms: 0, payload }
}

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function mockRect(el: Element, rect: Partial<DOMRect>): void {
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    x: rect.x ?? rect.left ?? 0,
    y: rect.y ?? rect.top ?? 0,
    left: rect.left ?? rect.x ?? 0,
    top: rect.top ?? rect.y ?? 0,
    right: rect.right ?? ((rect.x ?? rect.left ?? 0) + (rect.width ?? 0)),
    bottom: rect.bottom ?? ((rect.y ?? rect.top ?? 0) + (rect.height ?? 0)),
    width: rect.width ?? 0,
    height: rect.height ?? 0,
    toJSON: () => ({}),
  } as DOMRect)
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

  it('accepts snake_case region params from older edge payloads', async () => {
    const regions = new RegionRegistry()
    regions.register({
      key: 'feed',
      tabId: 42,
      rect: { x: 10, y: 20, width: 200, height: 300 },
    })
    const scroll = vi.fn(async () => ok({}))
    const handler = scrollRegionHandler({ regions, scroll })

    const result = await handler(42, {
      region_key: 'feed',
      direction: 'down',
      amount: 500,
      stop_when: { type: 'edge' },
      segments: 5,
    } as never, 1000)

    expect(result.ok).toBe(true)
    expect(scroll).toHaveBeenCalledExactlyOnceWith(42, {
      direction: 'down',
      distance_px: 500,
      segments: 5,
      x: 110,
      y: 170,
    }, 1000)
  })

  it('uses real CDP wheel for Douyin comments instead of mutating DOM scrollTop', async () => {
    document.body.innerHTML = `
      <main id="video">视频区域 点赞 分享</main>
      <aside id="comments" class="comment-panel">
        <div data-e2e="comment-list" id="list">
          <div data-e2e="comment-item" id="item1">
            <a href="https://www.douyin.com/user/ly">Ly</a>
            <div class="LvAtyU_f" id="body1">对于99%的人用豆包就行了。</div>
            <div class="w9APAHwo" id="share1">分享</div>
          </div>
        </div>
        <div>说点什么</div>
      </aside>
    `
    const comments = document.querySelector('#comments') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    const item1 = document.querySelector('#item1') as HTMLElement
    const body1 = document.querySelector('#body1') as HTMLElement
    const share1 = document.querySelector('#share1') as HTMLElement
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1200, height: 800 })
    mockRect(comments, { x: 1300, y: 0, width: 520, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 500, height: 650 })
    mockRect(item1, { x: 1320, y: 140, width: 500, height: 120 })
    mockRect(body1, { x: 1380, y: 178, width: 300, height: 28 })
    mockRect(share1, { x: 1760, y: 204, width: 48, height: 24 })
    Object.defineProperty(comments, 'clientHeight', { value: 400, configurable: true })
    Object.defineProperty(comments, 'scrollHeight', { value: 1200, configurable: true })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    Object.defineProperty(list, 'scrollTop', {
      get: () => 0,
      set: () => {},
      configurable: true,
    })
    comments.scrollBy = vi.fn()
    list.scrollBy = vi.fn()

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 600, height: 820 },
    })
    let wheelCount = 0
    const scroll = vi.fn(async (_tabId, _params) => {
      wheelCount += 1
      if (wheelCount === 1) {
        body1.textContent = '下一页评论已经加载。'
      }
      return ok({})
    })
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomExecution() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'comment_region_wheel',
      moved: true,
      forwardProgress: true,
      reason: 'comment_window_advanced',
      newVisibleItemCount: 1,
    }))
    expect(scroll).toHaveBeenCalled()
    expect(list.scrollBy).not.toHaveBeenCalled()
    const wheelParams = scroll.mock.calls[0]?.[1]
    expect(wheelParams).toEqual(expect.objectContaining({
      direction: 'down',
      distance_px: 500,
    }))
    expect(wheelParams.x).toBeGreaterThanOrEqual(1480)
    expect(wheelParams.x).toBeLessThan(1660)
    expect(wheelParams.y).toBeGreaterThanOrEqual(350)
    expect(wheelParams.y).toBeLessThanOrEqual(380)
  })

  it('does not let DOM fallback fields overwrite successful CDP wheel evidence', async () => {
    document.body.innerHTML = `
      <aside id="comments">
        <div data-e2e="comment-list" id="list">
          <div data-e2e="comment-item" id="item1">
            <a id="author1" href="https://www.douyin.com/user/ly">Ly</a>
            <div class="LvAtyU_f" id="body1">对于99%的人用豆包就行了。</div>
          </div>
        </div>
      </aside>
    `
    const comments = document.querySelector('#comments') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    const item1 = document.querySelector('#item1') as HTMLElement
    const author1 = document.querySelector('#author1') as HTMLElement
    const body1 = document.querySelector('#body1') as HTMLElement
    mockRect(comments, { x: 1300, y: 0, width: 520, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 500, height: 650 })
    mockRect(item1, { x: 1320, y: 140, width: 500, height: 120 })
    mockRect(author1, { x: 1380, y: 146, width: 80, height: 24 })
    mockRect(body1, { x: 1380, y: 178, width: 300, height: 28 })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    Object.defineProperty(list, 'scrollTop', { value: 0, configurable: true })

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 600, height: 820 },
    })
    const scroll = vi.fn(async () => {
      body1.textContent = '下一批评论已经出现。'
      return ok({ moved: false, reason: 'dom_scroll_container_not_moved' })
    })
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomExecution() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'comment_region_wheel',
      moved: true,
      forwardProgress: true,
      reason: 'comment_window_advanced',
    }))
  })

  it('reports no movement when CDP wheel only changes scrollTop but visible comments do not change', async () => {
    document.body.innerHTML = `
      <main id="video">视频区域 点赞 分享</main>
      <aside id="panel">
        <div id="list" data-e2e="comment-list">
          <div data-e2e="comment-item">Ly<span>对于99%的人用豆包就行了。</span></div>
          <button class="comment-reply-expand-btn">展开18条回复</button>
        </div>
      </aside>
    `
    const panel = document.querySelector('#panel') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1200, height: 800 })
    mockRect(panel, { x: 1300, y: 0, width: 560, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 520, height: 650 })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    let listScrollTop = 0
    Object.defineProperty(list, 'scrollTop', {
      get: () => listScrollTop,
      set: value => { listScrollTop = Number(value) },
      configurable: true,
    })
    list.scrollBy = vi.fn(({ top }: ScrollToOptions) => { listScrollTop += Number(top ?? 0) })
    Object.defineProperty(panel, 'clientHeight', { value: 800, configurable: true })
    Object.defineProperty(panel, 'scrollHeight', { value: 800, configurable: true })
    panel.scrollBy = vi.fn()

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 620, height: 820 },
    })
    const scroll = vi.fn(async () => {
      listScrollTop += 500
      return ok({})
    })
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomExecution() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'comment_region_wheel',
      moved: false,
      reason: 'comment_region_wheel_not_moved',
    }))
    expect(list.scrollBy).not.toHaveBeenCalled()
    expect(panel.scrollBy).not.toHaveBeenCalled()
    expect(scroll).toHaveBeenCalled()
  })

  it('does not treat text-only jitter as forward comment-window progress', async () => {
    document.body.innerHTML = `
      <aside id="comments">
        <div data-e2e="comment-list" id="list">
          <div class="LvAtyU_f" id="body1">对于99%的人用豆包就行了。</div>
        </div>
      </aside>
    `
    const comments = document.querySelector('#comments') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    const body1 = document.querySelector('#body1') as HTMLElement
    mockRect(comments, { x: 1300, y: 0, width: 520, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 500, height: 650 })
    mockRect(body1, { x: 1380, y: 178, width: 300, height: 28 })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    Object.defineProperty(list, 'scrollTop', { value: 0, configurable: true })

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 600, height: 820 },
    })
    const scroll = vi.fn(async () => {
      body1.textContent = '只是按钮或文本抖动，不是新评论窗口。'
      return ok({})
    })
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomExecution() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'comment_region_wheel',
      moved: true,
      forwardProgress: false,
      reason: 'visible_text_signature_changed_without_item_window',
    }))
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
