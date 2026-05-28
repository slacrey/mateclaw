// @vitest-environment happy-dom
//
// Tests for the StopButton — bottom-center pill the user clicks to abort the
// in-flight agent task. The button's onClick is wired in C5 (visual-indicator
// content script) to chrome.runtime.sendMessage({ type: 'STOP_AGENT' }). Here
// we test the visual + behavior contract only.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StopButton } from './StopButton'

describe('StopButton', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  afterEach(() => {
    const stray = document.getElementById('mateclaw-stop-button')
    if (stray) stray.remove()
    const strayContainer = document.getElementById('mateclaw-stop-button-container')
    if (strayContainer) strayContainer.remove()
    vi.restoreAllMocks()
  })

  it('show() mounts a clickable button with high z-index', () => {
    const b = new StopButton()
    b.show({})
    const btn = document.getElementById('mateclaw-stop-button')
    expect(btn).not.toBeNull()
    // The button (not the container) re-enables pointer-events.
    expect(btn!.style.pointerEvents).toBe('auto')
    // Stop button must be at the very top of the z-stack — max int.
    expect(parseInt(btn!.style.zIndex || '0', 10) || parseInt(getContainerZ(), 10))
      .toBeGreaterThanOrEqual(2147483647)
  })

  it('show({ suppressed: true }) does NOT mount anything (MCP mode)', () => {
    const b = new StopButton()
    b.show({ suppressed: true })
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
  })

  it('show() includes the "Stop Agent" label and a stop icon SVG', () => {
    const b = new StopButton()
    b.show({})
    const btn = document.getElementById('mateclaw-stop-button')!
    expect(btn.textContent).toContain('Stop Agent')
    const svg = btn.querySelector('svg')
    expect(svg).not.toBeNull()
  })

  it('onClick() registers a callback that fires when the button is clicked', () => {
    const b = new StopButton()
    const cb = vi.fn()
    b.onClick(cb)
    b.show({})
    const btn = document.getElementById('mateclaw-stop-button')!
    btn.click()
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('onClick() set BEFORE show() still fires after show()', () => {
    const b = new StopButton()
    const cb = vi.fn()
    b.onClick(cb)
    b.show({})
    document.getElementById('mateclaw-stop-button')!.click()
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('onClick() set AFTER show() also fires', () => {
    const b = new StopButton()
    const cb = vi.fn()
    b.show({})
    b.onClick(cb)
    document.getElementById('mateclaw-stop-button')!.click()
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('hide() removes the button after the fade', async () => {
    vi.useFakeTimers()
    const b = new StopButton()
    b.show({})
    expect(document.getElementById('mateclaw-stop-button')).not.toBeNull()
    b.hide()
    await vi.advanceTimersByTimeAsync(400)
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
    vi.useRealTimers()
  })

  it('double show() is idempotent (only one button in the DOM)', () => {
    const b = new StopButton()
    b.show({})
    b.show({})
    expect(document.querySelectorAll('#mateclaw-stop-button').length).toBe(1)
  })

  it('hide() before show() is a no-op (does not throw)', () => {
    const b = new StopButton()
    expect(() => b.hide()).not.toThrow()
  })

  it('prefers-reduced-motion: button starts in final position with shortened transition (P1-8)', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true,
      media: '(prefers-reduced-motion: reduce)',
      addEventListener: () => {},
      removeEventListener: () => {},
      onchange: null,
      dispatchEvent: () => false,
      addListener: () => {},
      removeListener: () => {},
    } as MediaQueryList)

    const b = new StopButton()
    b.show({})
    const btn = document.getElementById('mateclaw-stop-button')!
    // Reduced motion skips the slide-in: button starts already at translateY(0)
    // / opacity 1 rather than starting at translateY(100px) / opacity 0.
    expect(btn.style.transform).toBe('translateY(0)')
    expect(btn.style.opacity).toBe('1')
    // And the transition is collapsed to ~30ms (functionally instant).
    const match = /transform\s+(\d+)ms/.exec(btn.style.transition)
    expect(match).not.toBeNull()
    expect(parseInt(match![1], 10)).toBeLessThanOrEqual(30)
  })

  it('prefers-reduced-motion: CSS @media override stylesheet is injected (P1-8 belt-and-suspenders)', () => {
    const b = new StopButton()
    b.show({})
    const style = document.getElementById('mateclaw-stop-button-style')
    expect(style).not.toBeNull()
    // The injected rule must carry the CSS media-query form for the audit
    // script's invariant #7 grep.
    expect(style!.textContent).toContain('@media (prefers-reduced-motion: reduce)')
  })
})

function getContainerZ(): string {
  const container = document.getElementById('mateclaw-stop-button-container')
  return container ? container.style.zIndex : '0'
}
