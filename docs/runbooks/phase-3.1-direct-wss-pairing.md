# Phase 3.1 — Direct-WSS + One-Click Pairing Runbook

## What changed vs. Phase 1/2

The Chrome extension now connects to the Control Plane **directly over WebSocket**
and authenticates by carrying a Personal Access Token in the
`Sec-WebSocket-Protocol` subprotocol. The **Native-Messaging (NH) bridge is no
longer required** for normal use — it remains only as an optional path for local
Claude-Code / Claude-Desktop style integrations.

End-user install drops from 4 steps (extension + copy ID + install NH host +
restart Chrome) to effectively **2**: install the extension, then click
**Connect Browser** in the already-logged-in admin UI. The PAT is minted
server-side and pushed straight into the extension — it never touches the
clipboard.

## Architecture (mirrors the official Claude-in-Chrome extension)

```
┌─────────────┐  chrome.runtime.sendMessage(EXT_ID, {pair, pat, serverUrl})  ┌──────────────┐
│  admin UI   │ ───────────────────────────────────────────────────────────►│  extension   │
│ (logged in) │   (allowed via manifest externally_connectable whitelist)    │ service worker│
└──────┬──────┘                                                              └──────┬───────┘
       │ POST /api/v1/browser/pairing/mint-token  (admin JWT)                       │ new WebSocket(url,
       ▼                                                                            │  ['mateclaw.edge.v1',
┌─────────────────────────────────────────────────────────────────────┐           │   'bearer.<pat>'])
│ Control Plane  /api/v1/browser/pairing/*  +  /api/v1/browser/edge (WS)│◄──────────┘
│ EdgeAuthInterceptor validates the bearer from Authorization (NH) OR    │
│ from the Sec-WebSocket-Protocol entry (browser). EdgeWebSocketHandler  │
│ (SubProtocolCapable) echoes only `mateclaw.edge.v1`.                   │
└─────────────────────────────────────────────────────────────────────┘
```

Deterministic unpacked-extension id (pinned `key` in the manifest):
`bjdhmojdiahokgfcaahphcjgcnffbonf`.

## Build

```bash
# 1. Admin UI → server static (vite directly; the packaged `pnpm build` runs a
#    snowflake-precision check script that may be absent in some checkouts)
cd mateclaw-ui && pnpm install && pnpm exec vite build

# 2. Extension → mateclaw-extension/dist  (loadable unpacked)
cd ../mateclaw-extension && pnpm install && pnpm build

# 3. Control Plane (dev) — serves the UI built in step 1 on :18088
cd ../mateclaw-server && mvn spring-boot:run
```

## One-click pairing (the happy path)

1. Chrome → `chrome://extensions` → enable **Developer mode** → **Load unpacked**
   → select `mateclaw-extension/dist`. (Because the manifest pins a `key`, the id
   is always `bjdhmojdiahokgfcaahphcjgcnffbonf`.)
2. Open `http://localhost:18088/` and log in (`admin / admin123`).
3. **Settings → Browser** (`/settings/browser`). The card pings the extension:
   - grey "Not detected" → the extension isn't installed / not on a whitelisted origin.
   - yellow "Detected, not connected" → ready to pair.
4. Optionally name the device, click **Connect Browser**. The UI mints a
   browser-scoped PAT, pushes `{pat, serverUrl}` into the extension, and polls
   until the pill turns green **Connected**.
5. Done. The extension now holds an open authenticated WSS and will reconnect
   automatically (ping every 20 s, exponential backoff on drop).

**Disconnect**: the same card's **Disconnect** button unpairs the extension and
best-effort revokes the PAT.

## Manual fallback (origin not whitelisted)

If the admin UI is served from an origin **not** in the extension manifest's
`externally_connectable.matches` (defaults: `http://localhost:18088`,
`http://localhost:5173`), one-click can't reach the extension. Use the
**extension sidepanel → Settings**: paste the Server URL
(`ws://<host>/api/v1/browser/edge`) and a PAT, then **Save & Connect**. Still no
NH host required.

To enable one-click on a fixed production domain: add `https://<domain>/*` to
`externally_connectable.matches` in **both** `manifest.json` and
`public/manifest.json`, add the same origin to `ALLOWED_EXTERNAL_ORIGINS` in
`src/sw/index.ts`, and rebuild the extension.

## Smoke checklist

- [ ] Fresh Chrome profile, load unpacked → id is `bjdhmojdiahokgfcaahphcjgcnffbonf`.
- [ ] Admin UI **Settings → Browser** shows yellow "Detected".
- [ ] **Connect** → pill turns green; server log shows an edge session registered.
- [ ] Run an agent turn using `extension_browser_navigate` + `_click`; CDP events land.
- [ ] **Disconnect** → pill returns to yellow.
- [ ] Manual sidepanel paste connects from a non-whitelisted origin.
- [ ] NH bridge still connects when explicitly enabled (no regression).

## Auth/security notes

- The PAT rides in `Sec-WebSocket-Protocol`, **not** the URL — so it never lands
  in server access logs (unlike a `?token=` query string).
- `/api/v1/browser/edge` is `permitAll` in `SecurityConfig` (the JWT filter can't
  read a browser WS handshake), exactly like `/api/v1/talk/ws`. The real gate is
  `EdgeAuthInterceptor.beforeHandshake`, which 401s any bad/missing token before
  the socket upgrades.
- The WS endpoint admits `chrome-extension://*` origins; auth still gates every
  connection.
