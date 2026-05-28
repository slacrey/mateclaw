/**
 * visual-indicator content script — the document_idle, top-frame-only wiring
 * layer that owns the three visual affordances (PhantomCursor / GlowBorder /
 * StopButton) and routes chrome.runtime.onMessage envelopes to them.
 *
 * Message protocol (SW → CS):
 *
 *   { type: 'SHOW_AGENT_INDICATORS', isMcp?: boolean }
 *       Mount cursor + glow + (if !isMcp) stop button.
 *
 *   { type: 'HIDE_AGENT_INDICATORS' }
 *       Tear everything down.
 *
 *   { type: 'INDICATOR_CURSOR', x, y }
 *       Move the phantom cursor. Responds asynchronously after the CSS
 *       transition resolves (or the 220ms fallback): { ok: true, arrived_at_ms }.
 *       Returns `true` from the listener so chrome keeps the channel open
 *       (MV3 onMessage convention).
 *
 *   { type: 'TOOL_USE_HIDE' }
 *       Temporarily hide all indicators (used during screenshot capture) while
 *       preserving the prior visible-set so TOOL_USE_SHOW can restore it.
 *
 *   { type: 'TOOL_USE_SHOW' }
 *       Restore the visible-set captured at TOOL_USE_HIDE time.
 *
 * Stop button click → chrome.runtime.sendMessage({ type: 'STOP_AGENT' }).
 *
 * Design choice — tab_ref stamping:
 *   The content script does NOT know its own tabId. Rather than try to query
 *   chrome.tabs (which content scripts can't), we send a simple internal
 *   { type: 'STOP_AGENT' } message. The SW's edge.outbound listener (D2)
 *   reads sender.tab.id to identify which tab the click came from, then
 *   wraps that into the proper edge protocol envelope
 *   ({ kind: EdgeMessageKind.IndicatorStopClicked, payload: { tab_ref } })
 *   before forwarding to the Native Host. Keeping the CS->SW message simple
 *   means we don't have to import the edge-protocol module into a content
 *   script (which runs in the isolated world and cannot share modules with
 *   the SW anyway).
 *
 * Idempotent install: re-imports are short-circuited by a window-scoped flag.
 * This matches the project's existing pattern (see a11y-tree.ts).
 *
 * Top-frame only: the manifest declares all_frames: false, but we also guard
 * defensively here since the IIFE could be invoked from a test harness.
 */

import { PhantomCursor } from './visual/PhantomCursor'
import { GlowBorder } from './visual/GlowBorder'
import { StopButton } from './visual/StopButton'

declare global {
  interface Window {
    __mateclaw_visual_indicator_installed?: true
  }
}

;(function install(): void {
  if (typeof window === 'undefined') return
  if (window.__mateclaw_visual_indicator_installed) return
  // Defensive top-frame check — in real life the manifest enforces this, but
  // re-injection from a test or devtools shouldn't double-listen.
  if (window.top !== window.self) {
    window.__mateclaw_visual_indicator_installed = true
    return
  }
  window.__mateclaw_visual_indicator_installed = true

  const cursor = new PhantomCursor()
  const glow = new GlowBorder()
  const stop = new StopButton()

  // Whether each indicator is "logically visible" (independent of the in-flight
  // fade animation). Mirrors what TOOL_USE_HIDE needs to remember so
  // TOOL_USE_SHOW can restore the same set.
  let cursorVisible = false
  let glowVisible = false
  let stopVisible = false
  // Sticky decision from SHOW_AGENT_INDICATORS — if isMcp was set, we keep the
  // stop button suppressed across TOOL_USE_HIDE / TOOL_USE_SHOW cycles too.
  let suppressStop = false

  stop.onClick(() => {
    // Send a minimal internal message; SW translates this into the proper
    // edge envelope using sender.tab.id (see Design choice block above).
    try {
      chrome.runtime.sendMessage({ type: 'STOP_AGENT' })
    } catch {
      // If chrome.runtime is missing (e.g. SW reload mid-click), silently swallow.
      // The user will see no immediate feedback; the SW heartbeat / reconnect
      // path will recover.
    }
  })

  function showAll(isMcp: boolean): void {
    suppressStop = isMcp
    if (!cursorVisible) {
      // Mount at a safe off-screen-ish origin (0,0). The next INDICATOR_CURSOR
      // will reposition before the user perceives the initial spawn.
      cursor.mount(0, 0)
      cursorVisible = true
    }
    if (!glowVisible) {
      glow.show()
      glowVisible = true
    }
    if (!isMcp && !stopVisible) {
      stop.show({})
      stopVisible = true
    }
  }

  function hideAll(): void {
    if (cursorVisible) {
      cursor.unmount()
      cursorVisible = false
    }
    if (glowVisible) {
      glow.hide()
      glowVisible = false
    }
    if (stopVisible) {
      stop.hide()
      stopVisible = false
    }
  }

  // Snapshot of the visible-set captured at TOOL_USE_HIDE time, restored on
  // TOOL_USE_SHOW. We do NOT clear suppressStop here — the MCP suppression is
  // sticky across the hide/show round-trip per research §4.3.
  let priorSet: { cursor: boolean; glow: boolean; stop: boolean } | null = null

  function toolUseHide(): void {
    priorSet = { cursor: cursorVisible, glow: glowVisible, stop: stopVisible }
    hideAll()
  }

  function toolUseShow(): void {
    const prev = priorSet
    if (!prev) {
      // No prior snapshot — default to the SHOW_AGENT_INDICATORS behaviour
      // honoring the sticky MCP suppression.
      showAll(suppressStop)
      return
    }
    if (prev.cursor) {
      cursor.mount(0, 0)
      cursorVisible = true
    }
    if (prev.glow) {
      glow.show()
      glowVisible = true
    }
    if (prev.stop && !suppressStop) {
      stop.show({})
      stopVisible = true
    }
    priorSet = null
  }

  chrome.runtime.onMessage.addListener(
    (
      msg: unknown,
      _sender: chrome.runtime.MessageSender,
      sendResponse: (response?: unknown) => void,
    ): boolean | undefined => {
      if (!msg || typeof msg !== 'object') return undefined
      const m = msg as { type?: unknown }
      if (typeof m.type !== 'string') return undefined

      switch (m.type) {
        case 'SHOW_AGENT_INDICATORS': {
          const isMcp = Boolean((msg as { isMcp?: unknown }).isMcp)
          showAll(isMcp)
          return undefined
        }
        case 'HIDE_AGENT_INDICATORS': {
          hideAll()
          // Allow a fresh SHOW to restart from a clean default-not-MCP state.
          suppressStop = false
          return undefined
        }
        case 'INDICATOR_CURSOR': {
          const { x, y } = msg as { x?: number; y?: number }
          if (typeof x !== 'number' || typeof y !== 'number') return undefined
          // If the cursor isn't mounted yet (rare race), mount lazily.
          if (!cursorVisible) {
            cursor.mount(x, y)
            cursorVisible = true
          }
          cursor.move(x, y).then(() => {
            try {
              sendResponse({ ok: true, arrived_at_ms: Date.now() })
            } catch {
              // sendResponse may throw if the channel was closed mid-flight.
              // Nothing useful to do here — caller will fall back on its timeout.
            }
          })
          // Return true to keep the message channel open for async sendResponse.
          return true
        }
        case 'TOOL_USE_HIDE': {
          toolUseHide()
          return undefined
        }
        case 'TOOL_USE_SHOW': {
          toolUseShow()
          return undefined
        }
        default:
          return undefined
      }
    },
  )
})()

export {}
