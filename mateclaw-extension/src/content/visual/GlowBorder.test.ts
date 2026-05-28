// @vitest-environment happy-dom
//
// Tests for the GlowBorder — a full-viewport inner-shadow halo that
// declares "the agent is acting on this tab". Visual contract per research §3
// + plan §C3. The border is purely affordance: it does not intercept events
// (pointer-events: none) and animates a slow opacity pulse.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GlowBorder } from './GlowBorder'

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

describe('GlowBorder', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    // Defensive — any stray <style id="mateclaw-glow-anim"> from a prior test
    // would mask the "single injection" assertion below. We clear head too.
    document.head.innerHTML = ''
  })

  afterEach(() => {
    const stray = document.getElementById('mateclaw-glow-border')
    if (stray) stray.remove()
    const style = document.getElementById('mateclaw-glow-anim')
    if (style) style.remove()
    vi.restoreAllMocks()
  })

  it('show() mounts outer + inner divs with high z-index', () => {
    const g = new GlowBorder()
    g.show()
    const outer = document.getElementById('mateclaw-glow-border')
    const inner = document.getElementById('mateclaw-glow-border-inner')
    expect(outer).not.toBeNull()
    expect(inner).not.toBeNull()
    expect(outer!.style.position).toBe('fixed')
    expect(outer!.style.pointerEvents).toBe('none')
    expect(parseInt(outer!.style.zIndex, 10)).toBeGreaterThan(2_000_000_000)
  })

  it('show() injects @keyframes <style> tag once, even on double show', () => {
    const g = new GlowBorder()
    g.show()
    g.show()
    const styles = document.querySelectorAll('style#mateclaw-glow-anim')
    expect(styles.length).toBe(1)
  })

  it('inner div carries the three-stop inset box-shadow', () => {
    const g = new GlowBorder()
    g.show()
    const inner = document.getElementById('mateclaw-glow-border-inner')!
    const bs = inner.style.boxShadow
    // Three "inset" stops separated by commas.
    expect(bs).toContain('inset')
    expect(bs.split(',').length).toBeGreaterThanOrEqual(3)
  })

  it('inner div animation references the mateclaw-pulse keyframes', () => {
    const g = new GlowBorder()
    g.show()
    const inner = document.getElementById('mateclaw-glow-border-inner')!
    expect(inner.style.animation).toContain('mateclaw-pulse')
  })

  it('double show() is idempotent — only one outer/inner pair exists', () => {
    const g = new GlowBorder()
    g.show()
    g.show()
    expect(document.querySelectorAll('#mateclaw-glow-border').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-glow-border-inner').length).toBe(1)
  })

  it('hide() removes the border element', async () => {
    vi.useFakeTimers()
    const g = new GlowBorder()
    g.show()
    g.hide()
    // hide() schedules removal after the opacity fade. Advance past it.
    await vi.advanceTimersByTimeAsync(400)
    expect(document.getElementById('mateclaw-glow-border')).toBeNull()
    vi.useRealTimers()
  })

  it('hide() called during SHOWING still cleanly tears down', async () => {
    vi.useFakeTimers()
    const g = new GlowBorder()
    g.show()
    // Tear down before VISIBLE settles (during the SHOWING phase).
    g.hide()
    await vi.advanceTimersByTimeAsync(500)
    expect(document.getElementById('mateclaw-glow-border')).toBeNull()
    vi.useRealTimers()
  })

  it('show() after hide() restores the border (state machine returns to HIDDEN)', async () => {
    vi.useFakeTimers()
    const g = new GlowBorder()
    g.show()
    g.hide()
    await vi.advanceTimersByTimeAsync(400)
    expect(document.getElementById('mateclaw-glow-border')).toBeNull()
    g.show()
    expect(document.getElementById('mateclaw-glow-border')).not.toBeNull()
    vi.useRealTimers()
  })

  it('prefers-reduced-motion: glow animation is none (P1-8)', () => {
    mockReducedMotion(true)
    const g = new GlowBorder()
    g.show()
    const inner = document.getElementById('mateclaw-glow-border-inner')!
    // Either inline animation explicitly set to 'none' OR computed style
    // reports none — the test accepts either.
    const inlineNone = inner.style.animation === 'none'
    const computedNone = window.getComputedStyle(inner).animation === 'none'
    expect(inlineNone || computedNone).toBe(true)
  })
})
