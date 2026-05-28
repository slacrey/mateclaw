// Service Worker entry point — Chrome MV3 background service worker.
// Owns the NativeBridge connection to the Go native host, wires the
// TabGroupManager + DebuggerManager + ActionExecutor + ActionRouter,
// and forwards inbound Edge messages to the sidepanel.

import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import { NativeBridge } from './native-bridge'
import { TabGroupManager } from './tab-group-manager'
import { DebuggerManager } from './debugger-manager'
import { ActionExecutor, ActionFailureError, type ActionHandler, type ActionHandlers } from './action/ActionExecutor'
import { TabRefResolver } from './action/tab-ref-resolver'
import { ActionRouter } from './action/action-router'
import type { ActionResult } from './action/types'

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

// DebuggerManager will be wired into per-kind handlers by B3-B8. The
// instance lives here so SW restarts re-create it cleanly.
const debuggerManager = new DebuggerManager(chrome)
// Keep a reference so unused-variable analysis (and merge-time greps for
// B3-B8) find the swap-in point.
void debuggerManager

const resolver = new TabRefResolver({
  tabGroupManager,
  chrome,
  subject: SUBJECT,
})

// -----------------------------------------------------------------
// TODO(B3-B8): Stub handler registry.
//
// Real handlers ship via parallel Codex tasks (10/11/12/13) — when they
// land, replace `stubHandlers` below with the real `makeAllHandlers(deps)`
// imported from './action/handlers'. The DebuggerManager + TabGroupManager
// + resolver are already in scope above.
// -----------------------------------------------------------------

function makeStubHandler<P>(kind: string): ActionHandler<P> {
  return async (): Promise<ActionResult> => {
    throw new ActionFailureError(
      'UNKNOWN_KIND',
      `handler '${kind}' is not yet wired in the SW — B3-B8 ship via Codex 10-13`,
      false,
    )
  }
}

const stubHandlers: ActionHandlers = {
  navigate:   makeStubHandler('navigate'),
  click:      makeStubHandler('click'),
  type:       makeStubHandler('type'),
  scroll:     makeStubHandler('scroll'),
  move_mouse: makeStubHandler('move_mouse'),
  wait:       makeStubHandler('wait'),
}

const executor = new ActionExecutor(stubHandlers)

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
