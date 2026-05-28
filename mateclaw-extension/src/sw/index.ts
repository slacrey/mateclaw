// Service Worker entry point — Chrome MV3 background service worker.
// Owns the NativeBridge connection to the Go native host and relays
// Edge messages to/from the sidepanel via chrome.runtime.sendMessage.

import { NativeBridge } from './native-bridge'

/** Canonical NM host name — must match com.mateclaw.browser_bridge manifest. */
const HOST = 'com.mateclaw.browser_bridge'

const bridge = new NativeBridge(HOST)

try {
  bridge.connect()
} catch (e) {
  console.error('[mateclaw][sw] connectNative failed', e)
}

// Forward inbound Edge messages from the native host to any active listeners
// (sidepanel, devtools, etc.).
bridge.onMessage(m => {
  chrome.runtime
    .sendMessage({ kind: 'edge.inbound', message: m })
    .catch(() => {
      // Ignore — no listeners open is normal when sidepanel is closed.
    })
})

// Listen for outbound Edge messages sent by the sidepanel.
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
