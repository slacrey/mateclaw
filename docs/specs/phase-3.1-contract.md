# Phase 3.1 — Frozen Interface Contract (Direct WSS + One-Click Pairing)

Status: **FROZEN 2026-05-29.** All three parallel tracks (A backend, B extension,
C admin UI) code against this document. Do **not** change a wire shape here without
updating all three tracks. See `docs/plans/2026-05-29-phase-3.1-direct-wss-mode.md`
for the why.

---

## 0. Deterministic extension identity

The unpacked extension pins a public `key` so its ID is stable across machines and
the admin UI can target it.

```
EXTENSION_ID = bjdhmojdiahokgfcaahphcjgcnffbonf
```

- **Track B** puts this `key` value into BOTH `manifest.json` and `public/manifest.json`:
  ```
  "key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAp4q3+BGtN22LCnOitTNwvBCcE0BhDxDETgpC5Yf7+t5itm283o2Oum7UFth6+PtPyVmp+t4weXc+MJmfsUlzy+R/+7AsPHkBBdR9dQRVtq/O7ZlPHIh4LH63A4q8a5heFM1deK5mBF6hj5hBeTtuTneUdVTEHZ2cX9bKRjplRAk/2EtWWhQSXIFG7jFOxtAREU2WE1OcFZfpY69RZCNTNh2iYJ12IaP8rmSyz2C0Hf5IedZFHLWSDU9qEIAd/TYlaU1pfcF30QU1ILqtmTp5P2pyMSsZqqLVW79MmV6UHCdtTCdsDNvZIWb+S+tPI/o79Hu3+s3uhiB8s4dqPIPNhQIDAQAB"
  ```
- **Track C** bakes `EXTENSION_ID` as a constant (overridable via build env
  `VITE_MATECLAW_EXTENSION_ID` for future store IDs).

---

## 1. WebSocket transport + auth (Track A ↔ Track B)

### Endpoint
```
{ws|wss}://<host>/api/v1/browser/edge
```
- localhost dev: `ws://localhost:18088/api/v1/browser/edge`
- The admin UI derives this from its own origin: `https→wss`, `http→ws`, same host:port,
  path `/api/v1/browser/edge`.

### Handshake auth — via `Sec-WebSocket-Protocol` (NOT a header, NOT the URL)

The extension opens:
```ts
new WebSocket(url, ['mateclaw.edge.v1', `bearer.${pat}`])
```
- The browser serializes these as request header
  `Sec-WebSocket-Protocol: mateclaw.edge.v1, bearer.<pat>`.
- **Track A** `EdgeAuthInterceptor.beforeHandshake` MUST:
  1. Read token from `Authorization: Bearer <t>` **OR** from the
     `Sec-WebSocket-Protocol` header (find the comma-separated entry shaped
     `bearer.<token>`, strip the `bearer.` prefix). Authorization path stays for the
     NH bridge (server-to-server) — do not break it.
  2. Validate via the existing JWT-then-PAT cascade (unchanged).
  3. On success stash `EdgePrincipal` as today.
- **Track A** MUST make the server **echo the accepted subprotocol**
  `mateclaw.edge.v1` in the 101 response (`Sec-WebSocket-Protocol: mateclaw.edge.v1`),
  otherwise the browser immediately closes the socket. Never echo the `bearer.*`
  token back.
- **Track A** `WebSocketConfig`: replace `.setAllowedOrigins("")` with
  `.setAllowedOriginPatterns("chrome-extension://*")` on the `/api/v1/browser/edge`
  registration so the browser handshake's `Origin: chrome-extension://<id>` is admitted.
  Auth is still the real gate.

### Post-handshake protocol — UNCHANGED from edge-protocol.md v1.x
The envelope, kinds, and state machine are exactly as today. Direct mode only changes
*who* is the WS client. Specifically the **session_id ownership** moves into the SW:
1. Extension sends `HELLO` with `session_id:""`, payload includes
   `{ agent_version, device_id, device_name }`.
2. Server replies `HELLO_ACK` with `payload.session_id` (already implemented).
3. **The extension captures that `session_id` and stamps it on every subsequent
   outbound frame** — this is the job the NH bridge used to do. Update the P0-1
   comment in `edge-protocol.ts` to note that in direct mode the SW is the stamper.

---

## 2. `externally_connectable` message protocol (Track B ↔ Track C)

The admin UI (a normal web page on a whitelisted origin) talks to the extension via
`chrome.runtime.sendMessage(EXTENSION_ID, msg, callback)`. **Track B** registers
`chrome.runtime.onMessageExternal` and handles exactly these message `type`s. Every
handler MUST validate `sender.origin` is in the whitelist before acting, and MUST
`return true` to keep the async `sendResponse` channel open.

### `ping` — extension liveness probe
Request:  `{ type: 'ping' }`
Response: `{ alive: true, deviceName: string|null, connected: boolean, deviceId: string }`
- `connected` = DirectBridgeClient currently has an OPEN socket with a session_id.

### `pair` — push credentials + connect
Request:
```
{ type: 'pair', pat: string, serverUrl: string, deviceName?: string }
```
- `serverUrl` is the full WS URL (`ws(s)://host:port/api/v1/browser/edge`).
- Response (success): `{ ok: true }`
- Response (failure): `{ ok: false, error: string }`
- Behaviour: persist `{ serverUrl, pat, deviceName }` to `chrome.storage.local`,
  then (re)connect the DirectBridgeClient. Acking `{ok:true}` means *stored +
  connect initiated*, not *connected* — the admin UI then polls `ping` for
  `connected:true`.

### `unpair` — disconnect + forget
Request:  `{ type: 'unpair' }`
Response: `{ ok: true }`
- Behaviour: disconnect the socket, clear `serverUrl` + `pat` from storage (keep
  `deviceId`).

### Origin whitelist (manifest `externally_connectable.matches`)
```
"externally_connectable": {
  "matches": [
    "http://localhost:18088/*",
    "http://localhost:5173/*"     // Vite dev server (pnpm dev) proxying to 18088
    // PROD: add "https://<your-mateclaw-domain>/*" and rebuild the extension
  ]
}
```
> `localhost:5173` is included so the admin UI works under `pnpm dev` too. Whatever
> origin the admin UI is served from must be in this list AND must equal the origin
> Track C reads `location.origin` from.

---

## 3. PAT mint endpoint (Track A ↔ Track C)

A REST endpoint the admin UI calls (with its normal JWT session) to mint a
browser-scoped PAT for the logged-in user, just before pushing it to the extension.

```
POST /api/v1/browser/pairing/mint-token
Auth: normal admin JWT (existing SecurityFilterChain)
Request body: { "deviceName": "Work Laptop" }   // optional, defaults to "browser-extension"
Response 200: {
  "token": "mc_xxxxxxxx...",      // plaintext, shown once — admin UI forwards to extension, never displays
  "tokenId": "<id-string>",        // for later revoke
  "expiresAt": "2026-06-29T..."    // ISO-8601
}
```
- Reuses `PersonalAccessTokenService` create path. Name the PAT
  `"browser-extension/<deviceName>"`. Scope: `browser:edge` (string is fine even if
  per-scope enforcement isn't wired yet — forward-compat with the TODO in
  EdgeAuthInterceptor). TTL: 90 days (reasonable default; not a security-sensitive
  decision here).
- Optional companion for [Disconnect]:
  ```
  POST /api/v1/browser/pairing/revoke-token   body: { "tokenId": "..." }   → 200 { "ok": true }
  ```
  If revoke is more than a thin wrapper, Track A may stub it and Track C degrades
  gracefully (unpair the extension regardless).

---

## 4. Things explicitly OUT of scope for these tracks
- No changes to the Phase 2 action / grounding / snapshot / screenshot stacks.
- No removal of the NH bridge or `nativeMessaging` permission (it stays as the
  optional Claude-Code path; just no longer auto-connected on SW start).
- No production-domain hardcoding beyond the documented placeholder.
