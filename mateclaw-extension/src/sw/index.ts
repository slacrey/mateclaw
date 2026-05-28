// Service Worker entry point — Chrome MV3 background service worker.
// Owns the NativeBridge connection to the Go native host, wires the
// TabGroupManager + DebuggerManager + ActionExecutor + ActionRouter,
// and forwards inbound Edge messages to the sidepanel.

import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import { NativeBridge } from './native-bridge'
import { TabGroupManager } from './tab-group-manager'
import { DebuggerManager } from './debugger-manager'
import { ActionExecutor, type ActionHandlers } from './action/ActionExecutor'
import { TabRefResolver } from './action/tab-ref-resolver'
import { ActionRouter } from './action/action-router'
import { SnapshotRequestHandler } from './snapshot-request-handler'
import { VisualCoordinator } from './visual-coordinator'
import { navigateHandler } from './action/handlers/navigate'
import { clickHandler } from './action/handlers/click'
import { typeHandler } from './action/handlers/type'
import { scrollHandler } from './action/handlers/scroll'
import { moveMouseHandler } from './action/handlers/move_mouse'
import { waitHandler } from './action/handlers/wait'
import type { Point } from '../lib/windmouse'

/** Canonical NM host name — must match com.mateclaw.browser_bridge manifest. */
const HOST = 'com.mateclaw.browser_bridge'

/**
 * Phase 2 hardcoded subject. Phase 4 will derive this from the
 * authenticated user (sidepanel auth flow). For now everything routes
 * to a single "default" subject so the TabGroupManager has somewhere to
 * stash main-tab bindings.
 */
const SUBJECT = 'default'

const bridge = new NativeBridge(HOST)

try {
  bridge.connect()
} catch (e) {
  console.error('[mateclaw][sw] connectNative failed', e)
}

const sendUp = (msg: EdgeMessage): void => {
  try {
    bridge.send(msg)
  } catch (e) {
    // Best-effort — NH may be disconnected during reconnect window.
    console.error('[mateclaw][sw] sendUp failed', e)
  }
}

const tabGroupManager = new TabGroupManager(chrome, sendUp)
// Fire-and-forget rehydrate; subsequent reads await internal #loadGroups
// which handles the race correctly.
tabGroupManager.load().catch(e => {
  console.error('[mateclaw][sw] TabGroupManager.load failed', e)
})

// DebuggerManager: shared CDP attach/detach + send across all CDP-using
// handlers (click/type/scroll/move_mouse). The instance lives here so
// SW restarts re-create it cleanly.
const debuggerManager = new DebuggerManager(chrome)

const resolver = new TabRefResolver({
  tabGroupManager,
  chrome,
  subject: SUBJECT,
})

// -----------------------------------------------------------------
// Real handler registry (Wave 3 task 0 — swapped in from B3-B8 stubs).
//
// All handlers built on Wave-2 deliverables:
//   navigate   — chrome.tabs.update + webNavigation race
//   click      — CDP Input.dispatchMouseEvent press/release with hold
//   type       — CDP Input.dispatchKeyEvent keyDown+char+keyUp per char
//   scroll     — CDP Input.dispatchMouseWheelEvent segmented
//   move_mouse — WindMouse waypoints over CDP Input.dispatchMouseEvent
//   wait       — three strategies (time / load_state / network_idle)
//
// Per-tab cursor state shared by move_mouse so consecutive moves continue
// from the previous arrival point.
// -----------------------------------------------------------------

const cursorState = new Map<number, Point>()

const handlers: ActionHandlers = {
  navigate:   navigateHandler(chrome),
  click:      clickHandler({ debugger: debuggerManager }),
  type:       typeHandler({ debugger: debuggerManager }),
  scroll:     scrollHandler({ debugger: debuggerManager }),
  move_mouse: moveMouseHandler({ debugger: debuggerManager, cursorState }),
  wait:       waitHandler({ chrome }),
}

const executor = new ActionExecutor(handlers)

/**
 * Map of in-flight ActionExecutor runs keyed by request msg_id. Phase 2-1
 * scaffolds it — the router calls `.abort()` on cancel — but no handler
 * passes the AbortSignal through to its work yet. Wave 3 will wire the
 * signal into navigate/click/etc. for true mid-action cancellation.
 */
const inflight = new Map<string, AbortController>()

const router = new ActionRouter({
  resolver,
  executor,
  sendUp,
  inflight,
})

const snapshotHandler = new SnapshotRequestHandler({
  resolver,
  sendUp,
})

const visualCoordinator = new VisualCoordinator({
  resolver,
  sendUp,
  chrome,
})

// -----------------------------------------------------------------
// Inbound Edge messages
// -----------------------------------------------------------------

bridge.onMessage(m => {
  // Route action.* / indicator.stop_clicked through the ActionRouter.
  if (
    m.kind === EdgeMessageKind.ActionExecute ||
    m.kind === EdgeMessageKind.ActionCancel ||
    m.kind === EdgeMessageKind.IndicatorStopClicked
  ) {
    router.handle(m).catch(e => {
      console.error('[mateclaw][sw] ActionRouter.handle threw', e)
    })
  }

  if (m.kind === EdgeMessageKind.A11ySnapshotRequest) {
    snapshotHandler.handle(m).catch(e => {
      console.error('[mateclaw][sw] SnapshotRequestHandler.handle threw', e)
    })
  }

  if (VisualCoordinator.handles(m.kind)) {
    visualCoordinator.handle(m).catch(e => {
      console.error('[mateclaw][sw] VisualCoordinator.handle threw', e)
    })
  }

  // Forward inbound Edge messages to any active listeners (sidepanel,
  // devtools, etc.) — same behaviour as Phase 1.
  chrome.runtime
    .sendMessage({ kind: 'edge.inbound', message: m })
    .catch(() => {
      // Ignore — no listeners open is normal when sidepanel is closed.
    })
})

// -----------------------------------------------------------------
// Outbound (from sidepanel) — pass-through unchanged from Phase 1
// -----------------------------------------------------------------

chrome.runtime.onMessage.addListener(
  (req: unknown, _sender, sendResponse: (r: unknown) => void) => {
    const r = req as { kind?: string; message?: unknown }
    if (r?.kind !== 'edge.outbound') return
    try {
      bridge.send(r.message as Parameters<typeof bridge.send>[0])
      sendResponse({ ok: true })
    } catch (e) {
      sendResponse({ ok: false, error: String(e) })
    }
    return true // keep sendResponse channel open asynchronously
  },
)
