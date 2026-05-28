// @vitest-environment happy-dom
//
// Tests for the PhantomCursor — a fixed-position overlay element that the
// SW drives during agent activity. The cursor itself does NOT dispatch real
// input; it's a pure visual affordance so the user sees where the synthesized
// pointer "is" before the CDP-issued click lands.
//
// happy-dom is the project default test env (see vite.config.ts). It does NOT
// fire `transitionend`, so the move() Promise must resolve via the 220ms
// fallback timer. We assert that behaviour explicitly.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PhantomCursor } from './PhantomCursor'

function mockReducedMotion(matches: boolean): void {
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches,
    media: '(prefers-reduced-motion: reduce)',
    addEventListener: () => {},
    removeEventListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
    addListener: () => {},
    removeListener: () => {},
  } as unknown as MediaQueryList)
}

describe('PhantomCursor', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.useRealTimers()
  })

  afterEach(() => {
    // Defensive cleanup — every test should end with no phantom cursor in DOM
    // so the next one starts clean even when a test fails mid-way.
    const stray = document.getElementById('mateclaw-phantom-cursor')
    if (stray) stray.remove()
    vi.restoreAllMocks()
  })

  it('mounts a fixed-position cursor element with high z-index', () => {
    const c = new PhantomCursor()
    c.mount(50, 50)
    const el = document.getElementById('mateclaw-phantom-cursor')
    expect(el).not.toBeNull()
    expect(el!.style.position).toBe('fixed')
    expect(parseInt(el!.style.zIndex, 10)).toBeGreaterThan(2_000_000_000)
    expect(el!.style.pointerEvents).toBe('none')
    expect(el!.style.top).toBe('0px')
    expect(el!.style.left).toBe('0px')
    expect(el!.style.willChange).toBe('transform')
    expect(el!.style.transform).toContain('translate3d(50px, 50px')
  })

  it('mount contains two SVG cursor icons (plain + styled), only one visible', () => {
    const c = new PhantomCursor()
    c.mount(0, 0)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    const svgs = el.querySelectorAll('svg')
    expect(svgs.length).toBe(2)
    // Exactly one of them should be hidden via display:none (the OTHER theme).
    const hidden = Array.from(svgs).filter(
      (s) => (s as SVGElement).style.display === 'none',
    )
    expect(hidden.length).toBe(1)
  })

  it('move() updates the transform synchronously and the Promise resolves via fallback', async () => {
    vi.useFakeTimers()
    const c = new PhantomCursor()
    c.mount(0, 0)
    // happy-dom never fires transitionend → only the 220ms fallback resolves.
    const p = c.move(100, 100)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    expect(el.style.transform).toContain('translate3d(100px, 100px')
    // Advance past the 220ms fallback.
    await vi.advanceTimersByTimeAsync(230)
    await expect(p).resolves.toBeUndefined()
  })

  it('move() Promise resolves when transitionend fires before the fallback', async () => {
    vi.useFakeTimers()
    const c = new PhantomCursor()
    c.mount(0, 0)
    const p = c.move(200, 200)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    // Synthesize a transitionend before the 220ms fallback. The Promise must
    // resolve via the event path, not the timer path.
    el.dispatchEvent(new Event('transitionend'))
    await expect(p).resolves.toBeUndefined()
    vi.useRealTimers()
  })

  it('unmount removes the element from the DOM', () => {
    const c = new PhantomCursor()
    c.mount(10, 10)
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
    c.unmount()
    expect(document.getElementById('mateclaw-phantom-cursor')).toBeNull()
  })

  it('unmount is idempotent (safe to call twice or before mount)', () => {
    const c = new PhantomCursor()
    expect(() => c.unmount()).not.toThrow()
    c.mount(0, 0)
    c.unmount()
    expect(() => c.unmount()).not.toThrow()
    expect(document.getElementById('mateclaw-phantom-cursor')).toBeNull()
  })

  it('setStyle toggles between plain and styled SVG visibility', () => {
    const c = new PhantomCursor()
    c.mount(0, 0)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    const plain = el.querySelector('svg[data-theme="plain"]') as SVGElement
    const styled = el.querySelector('svg[data-theme="styled"]') as SVGElement
    expect(plain).not.toBeNull()
    expect(styled).not.toBeNull()

    c.setStyle('plain')
    expect(plain.style.display).toBe('')
    expect(styled.style.display).toBe('none')

    c.setStyle('styled')
    expect(plain.style.display).toBe('none')
    expect(styled.style.display).toBe('')
  })

  it('prefers-reduced-motion: cursor transition collapses to <=30ms (P1-8)', () => {
    mockReducedMotion(true)
    const c = new PhantomCursor()
    c.mount(0, 0)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    // Parse transition duration ("180ms" or "30ms" or "0ms") out of the inline style.
    const match = /transform\s+(\d+)ms/.exec(el.style.transition)
    expect(match).not.toBeNull()
    expect(parseInt(match![1]!, 10)).toBeLessThanOrEqual(30)
  })

  it('normal motion: transition uses the 180ms duration', () => {
    mockReducedMotion(false)
    const c = new PhantomCursor()
    c.mount(0, 0)
    const el = document.getElementById('mateclaw-phantom-cursor')!
    const match = /transform\s+(\d+)ms/.exec(el.style.transition)
    expect(match).not.toBeNull()
    expect(parseInt(match![1]!, 10)).toBe(180)
  })
})
