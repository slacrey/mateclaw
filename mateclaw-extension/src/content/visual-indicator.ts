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
 *   { type: 'SHOW_STATIC_INDICATOR', dismissed?: boolean }
 *       Show the passive "MateClaw is active in this tab group" pill. This is
 *       separate from the in-flight cursor/glow/stop affordance and is used on
 *       controlled tabs even while no action is running.
 *
 *   { type: 'HIDE_STATIC_INDICATOR' }
 *       Tear down the passive tab-group pill and its heartbeat.
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
import { StaticIndicator } from './visual/StaticIndicator'

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
  const staticIndicator = new StaticIndicator()

  // Whether each indicator is "logically visible" (independent of the in-flight
  // fade animation). Mirrors what TOOL_USE_HIDE needs to remember so
  // TOOL_USE_SHOW can restore the same set.
  let cursorVisible = false
  let glowVisible = false
  let stopVisible = false
  // Sticky decision from SHOW_AGENT_INDICATORS — if isMcp was set, we keep the
  // stop button suppressed across TOOL_USE_HIDE / TOOL_USE_SHOW cycles too.
  let suppressStop = false
  let staticVisible = false

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

  staticIndicator.onFocusMain(() => {
    try {
      chrome.runtime.sendMessage({ type: 'SWITCH_TO_MAIN_TAB' })
    } catch {
      // Runtime may be between MV3 wakeups; the static heartbeat will recover.
    }
  })

  staticIndicator.onDismiss(() => {
    staticVisible = false
    stopStaticHeartbeat()
    staticIndicator.hide()
    try {
      chrome.runtime.sendMessage({ type: 'DISMISS_STATIC_INDICATOR_FOR_GROUP' })
    } catch {
      // Best-effort preference update. Locally hiding keeps the page usable.
    }
  })

  function showAll(isMcp: boolean): void {
    suppressStop = isMcp
    // The passive group pill occupies the same bottom-center space as Stop
    // Agent. Keep its logical state but remove its DOM during active control.
    if (staticVisible) staticIndicator.hide()
    if (!cursorVisible) {
      // Mount near viewport center so the cursor is INSTANTLY VISIBLE the
      // moment indicators turn on — matches the official "Claude in Chrome"
      // takeover feel where the phantom is on-screen even before the agent
      // moves it. Previously we mounted at (0,0) which left the cursor in
      // the top-left corner until something sent INDICATOR_CURSOR — so a
      // navigate+observe task (no clicks → no move_mouse) never showed any
      // cursor. innerWidth/Height fall back to client/document size, then
      // to 1280x720, so a brand-new tab whose renderer hasn't laid out yet
      // still gets a sane on-screen origin.
      const vw = window.innerWidth || document.documentElement?.clientWidth || 1280
      const vh = window.innerHeight || document.documentElement?.clientHeight || 720
      cursor.mount(Math.round(vw / 2), Math.round(vh / 2))
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
    const hadActiveIndicator = cursorVisible || glowVisible || stopVisible
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
    if (hadActiveIndicator && staticVisible) staticIndicator.show()
  }

  // Snapshot of the visible-set captured at TOOL_USE_HIDE time, restored on
  // TOOL_USE_SHOW. We do NOT clear suppressStop here — the MCP suppression is
  // sticky across the hide/show round-trip per research §4.3.
  let priorSet: { cursor: boolean; glow: boolean; stop: boolean } | null = null
  let priorStaticVisible: boolean | null = null

  // -----------------------------------------------------------------
  // Phase 2.1 D3 — Watchdog timer that auto-unmounts overlays if the SW
  // stops pinging INDICATOR_HEARTBEAT. The SW's VisualCoordinator pings
  // every 5 s while indicators are SHOWN; we allow 3 misses (= 15 s)
  // before assuming the SW died and tearing things down so the user is
  // not left staring at a zombie cursor.
  // -----------------------------------------------------------------
  const WATCHDOG_TIMEOUT_MS = 15_000
  let watchdog: ReturnType<typeof setTimeout> | null = null

  function resetWatchdog(): void {
    if (watchdog !== null) clearTimeout(watchdog)
    watchdog = setTimeout(() => {
      watchdog = null
      // SW silently went away. Tear the overlays down so the page is usable.
      console.warn('[mateclaw][cs] visual-indicator watchdog fired — SW silent for ' +
        `${WATCHDOG_TIMEOUT_MS}ms; auto-unmounting overlays`)
      hideAll()
      suppressStop = false
    }, WATCHDOG_TIMEOUT_MS)
  }

  function stopWatchdog(): void {
    if (watchdog !== null) {
      clearTimeout(watchdog)
      watchdog = null
    }
  }

  const STATIC_HEARTBEAT_INTERVAL_MS = 5_000
  let staticHeartbeat: ReturnType<typeof setInterval> | null = null

  function showStaticIndicator(dismissed: boolean): void {
    if (dismissed) {
      hideStaticIndicator()
      return
    }
    staticVisible = true
    if (!cursorVisible && !glowVisible && !stopVisible) {
      staticIndicator.show()
    }
    startStaticHeartbeat()
  }

  function hideStaticIndicator(): void {
    staticVisible = false
    stopStaticHeartbeat()
    staticIndicator.hide()
  }

  function startStaticHeartbeat(): void {
    if (staticHeartbeat !== null) return
    staticHeartbeat = setInterval(() => {
      if (!staticVisible) return
      try {
        const ret = chrome.runtime.sendMessage({ type: 'STATIC_INDICATOR_HEARTBEAT' }) as
          | Promise<unknown>
          | undefined
        if (ret && typeof (ret as Promise<unknown>).then === 'function') {
          ;(ret as Promise<unknown>)
            .then(response => {
              if (!heartbeatAccepted(response)) hideStaticIndicator()
            })
            .catch(() => hideStaticIndicator())
        }
      } catch {
        hideStaticIndicator()
      }
    }, STATIC_HEARTBEAT_INTERVAL_MS)
  }

  function stopStaticHeartbeat(): void {
    if (staticHeartbeat === null) return
    clearInterval(staticHeartbeat)
    staticHeartbeat = null
  }

  function toolUseHide(): void {
    priorSet = { cursor: cursorVisible, glow: glowVisible, stop: stopVisible }
    priorStaticVisible = staticVisible
    hideAll()
    staticIndicator.hide()
  }

  function toolUseShow(): void {
    const prev = priorSet
    if (!prev) {
      // No prior snapshot — default to the SHOW_AGENT_INDICATORS behaviour
      // honoring the sticky MCP suppression.
      showAll(suppressStop)
      if (priorStaticVisible) staticIndicator.show()
      priorStaticVisible = null
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
    if (priorStaticVisible && !prev.cursor && !prev.glow && !prev.stop) {
      staticIndicator.show()
    }
    priorSet = null
    priorStaticVisible = null
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
          // D3: arm the watchdog so SW silence auto-unmounts after ~15s.
          resetWatchdog()
          return undefined
        }
        case 'HIDE_AGENT_INDICATORS': {
          hideAll()
          stopWatchdog()
          // Allow a fresh SHOW to restart from a clean default-not-MCP state.
          suppressStop = false
          return undefined
        }
        case 'SHOW_STATIC_INDICATOR': {
          const dismissed = Boolean((msg as { dismissed?: unknown }).dismissed)
          showStaticIndicator(dismissed)
          return undefined
        }
        case 'HIDE_STATIC_INDICATOR':
        case 'HIDE_STATIC_PILL': {
          hideStaticIndicator()
          return undefined
        }
        case 'INDICATOR_HEARTBEAT': {
          // D3: SW is still alive. Slide the watchdog deadline forward.
          resetWatchdog()
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

function heartbeatAccepted(response: unknown): boolean {
  if (response == null) return true
  if (typeof response !== 'object') return false
  const r = response as Record<string, unknown>
  if (typeof r.ok === 'boolean') return r.ok
  if (typeof r.success === 'boolean') return r.success
  return true
}

export {}
