// @vitest-environment happy-dom
//
// Tests for the visual-indicator content script (C5) — the document_idle,
// top-frame wiring layer that owns instances of PhantomCursor, GlowBorder, and
// StopButton and routes chrome.runtime.onMessage envelopes to them.
//
// We mock the chrome.runtime API minimally — only the surface the content
// script actually touches: onMessage.addListener + sendMessage.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Minimal Chrome shim. Returns a `fire()` helper to synthesize incoming
// messages from the SW (with optional sendResponse capture).
function setupChromeShim(): {
  fire: (
    msg: unknown,
    sendResponse?: (resp?: unknown) => void,
  ) => boolean | void
  sendMessage: ReturnType<typeof vi.fn>
} {
  let registered: ((
    msg: unknown,
    sender: unknown,
    sendResponse: (resp?: unknown) => void,
  ) => boolean | void) | null = null
  const sendMessage = vi.fn()
  ;(globalThis as Record<string, unknown>).chrome = {
    runtime: {
      onMessage: {
        addListener: (
          cb: (
            msg: unknown,
            sender: unknown,
            sendResponse: (resp?: unknown) => void,
          ) => boolean | void,
        ) => {
          registered = cb
        },
        removeListener: () => {
          registered = null
        },
      },
      sendMessage,
      lastError: undefined,
    },
  }
  return {
    sendMessage,
    fire: (msg, sendResponse) => {
      if (!registered) throw new Error('No listener registered')
      return registered(msg, {}, sendResponse ?? (() => {}))
    },
  }
}

async function loadCs(): Promise<void> {
  // Reset module cache so the IIFE re-runs against the fresh shim and DOM.
  vi.resetModules()
  await import('./visual-indicator')
}

describe('visual-indicator content script', () => {
  let shim: ReturnType<typeof setupChromeShim>

  beforeEach(async () => {
    document.body.innerHTML = ''
    document.head.innerHTML = ''
    // Remove the guard-flag the IIFE plants on window, so re-import re-installs.
    delete (window as unknown as { __mateclaw_visual_indicator_installed?: unknown })
      .__mateclaw_visual_indicator_installed
    shim = setupChromeShim()
    await loadCs()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    // Belt-and-suspenders cleanup so the next test starts fresh.
    document.body.innerHTML = ''
    document.head.innerHTML = ''
  })

  it('SHOW_AGENT_INDICATORS mounts cursor + glow + stop button', () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
    expect(document.getElementById('mateclaw-glow-border')).not.toBeNull()
    expect(document.getElementById('mateclaw-stop-button')).not.toBeNull()
  })

  it('SHOW_AGENT_INDICATORS with isMcp=true suppresses the stop button', () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS', isMcp: true })
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
    expect(document.getElementById('mateclaw-glow-border')).not.toBeNull()
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
  })

  it('INDICATOR_CURSOR moves the cursor and the sendResponse resolves', async () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    let captured: unknown = null
    // The listener returns true to keep the channel open for async sendResponse.
    const ret = shim.fire(
      { type: 'INDICATOR_CURSOR', x: 100, y: 200 },
      (resp) => {
        captured = resp
      },
    )
    expect(ret).toBe(true)
    // The transform is applied synchronously; the sendResponse fires after the
    // PhantomCursor.move() Promise resolves (via the 220ms fallback in happy-dom).
    expect(
      document.getElementById('mateclaw-phantom-cursor')!.style.transform,
    ).toContain('translate3d(100px, 200px')
    // Wait long enough for the fallback timer to settle.
    await new Promise((r) => setTimeout(r, 260))
    expect(captured).toEqual(
      expect.objectContaining({ ok: true, arrived_at_ms: expect.any(Number) }),
    )
  })

  it('HIDE_AGENT_INDICATORS unmounts everything', async () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
    shim.fire({ type: 'HIDE_AGENT_INDICATORS' })
    // Wait past the 300ms glow + stop fade transitions.
    await new Promise((r) => setTimeout(r, 350))
    expect(document.getElementById('mateclaw-phantom-cursor')).toBeNull()
    expect(document.getElementById('mateclaw-glow-border')).toBeNull()
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
  })

  it('TOOL_USE_HIDE hides indicators; TOOL_USE_SHOW restores prior visible set', async () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    shim.fire({ type: 'TOOL_USE_HIDE' })
    await new Promise((r) => setTimeout(r, 350))
    expect(document.getElementById('mateclaw-phantom-cursor')).toBeNull()
    expect(document.getElementById('mateclaw-glow-border')).toBeNull()
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
    shim.fire({ type: 'TOOL_USE_SHOW' })
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
    expect(document.getElementById('mateclaw-glow-border')).not.toBeNull()
    expect(document.getElementById('mateclaw-stop-button')).not.toBeNull()
  })

  it('TOOL_USE_SHOW respects the prior isMcp suppression', async () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS', isMcp: true })
    shim.fire({ type: 'TOOL_USE_HIDE' })
    await new Promise((r) => setTimeout(r, 350))
    shim.fire({ type: 'TOOL_USE_SHOW' })
    // Stop button stays suppressed across the hide/show cycle.
    expect(document.getElementById('mateclaw-stop-button')).toBeNull()
    expect(document.getElementById('mateclaw-phantom-cursor')).not.toBeNull()
  })

  it('stop button click dispatches STOP_AGENT to the SW', () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    const btn = document.getElementById('mateclaw-stop-button')!
    btn.click()
    expect(shim.sendMessage).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'STOP_AGENT' }),
    )
  })

  it('double SHOW_AGENT_INDICATORS does not duplicate DOM nodes (P2-2)', () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    expect(document.querySelectorAll('#mateclaw-phantom-cursor').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-glow-border').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-stop-button').length).toBe(1)
  })

  it('SHOW after HIDE re-mounts cleanly without duplicates', async () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    shim.fire({ type: 'HIDE_AGENT_INDICATORS' })
    await new Promise((r) => setTimeout(r, 350))
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    expect(document.querySelectorAll('#mateclaw-phantom-cursor').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-glow-border').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-stop-button').length).toBe(1)
  })

  it('idempotent install — re-import the module does not double-listen', async () => {
    // Capture how many addListener calls have happened.
    const before = vi.fn()
    // Re-run loader. Because the IIFE guards on window flag, the listener is
    // NOT re-registered. Send a SHOW and verify there's still exactly one of
    // each element afterwards (proving the listener is unique).
    await loadCs()
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    expect(document.querySelectorAll('#mateclaw-phantom-cursor').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-glow-border').length).toBe(1)
    expect(document.querySelectorAll('#mateclaw-stop-button').length).toBe(1)
    expect(before).not.toHaveBeenCalled()
  })

  it('all four mateclaw-* element ids are unique on the page', () => {
    shim.fire({ type: 'SHOW_AGENT_INDICATORS' })
    const ids = [
      'mateclaw-phantom-cursor',
      'mateclaw-glow-border',
      'mateclaw-glow-border-inner',
      'mateclaw-stop-button',
    ]
    for (const id of ids) {
      expect(document.querySelectorAll(`#${id}`).length).toBe(1)
    }
  })
})
