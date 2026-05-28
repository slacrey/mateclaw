# Phase 1 Foundation — Install and Smoke-Test Runbook

## Overview

Phase 1 delivers the three-process infrastructure that connects the MateClaw Control Plane to Chrome. A TypeScript/Node Native Host bridge runs on the user's machine, maintains a persistent authenticated WebSocket to the Java Control Plane (`ws://localhost:18088/api/v1/browser/edge`), and bridges Chrome's Native Messaging protocol to that connection. The Chrome MV3 Extension (Vue 3 sidepanel) provides a manual Ping button that fires a `ping` envelope end-to-end and displays the `pong` response. This proves auth, session registry, heartbeat, and stdio bridging all work. No browser automation (CDP) is included; that is Phase 2.

## Prerequisites

| Dependency | Minimum version |
|---|---|
| JDK | 21 (Temurin recommended) |
| Node.js | 20 LTS |
| pnpm | 10+ |
| Google Chrome | 116+ (supports MV3 sidepanel API) |

Verify:

```bash
java -version       # openjdk 21...
node --version      # v20.x.x
pnpm --version      # 10.x.x
```

## Build all three components

### 1. Control Plane (Java)

```bash
mvn -pl mateclaw-server -am package -DskipTests
```

The Spring Boot fat JAR is written to `mateclaw-server/target/mateclaw-server-*.jar`.

### 2. Native Host bridge (TypeScript/Node)

```bash
cd mateclaw-browser-bridge
pnpm install
pnpm build
```

`pnpm build` compiles the TypeScript source and produces a standalone binary (`bridge.exe` on Windows, `bridge` on macOS/Linux) in `mateclaw-browser-bridge/dist/`. The binary bundles Node so end users do not need Node installed.

### 3. Chrome Extension

```bash
cd mateclaw-extension
pnpm install
pnpm build
```

The built extension is written to `mateclaw-extension/dist/`.

## Install the Native Host manifest for Chrome

Chrome requires a JSON manifest file to allow an extension to talk to a native binary. After the B-stream finishes, the manifest will be at `mateclaw-browser-bridge/install/manifest/com.mateclaw.browser_bridge.json` and install scripts at `mateclaw-browser-bridge/install/install-windows.ps1`, `install-macos.sh`, and `install-linux.sh`.

The manifest format looks like:

```json
{
  "name": "com.mateclaw.browser_bridge",
  "description": "MateClaw browser agent native host",
  "path": "/absolute/path/to/bridge",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://<EXTENSION_ID>/"]
}
```

Replace `/absolute/path/to/bridge` with the actual path to the built binary and `<EXTENSION_ID>` with the ID shown on `chrome://extensions` after loading the unpacked extension.

### Windows

Run the install script as Administrator:

```powershell
.\mateclaw-browser-bridge\install\install-windows.ps1
```

The script writes the manifest path into the registry at:

```
HKCU\SOFTWARE\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge
```

You can verify with:

```powershell
Get-ItemProperty "HKCU:\SOFTWARE\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge"
```

### macOS

```bash
bash mateclaw-browser-bridge/install/install-macos.sh
```

The script copies the manifest to:

```
~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.mateclaw.browser_bridge.json
```

### Linux

```bash
bash mateclaw-browser-bridge/install/install-linux.sh
```

The script copies the manifest to:

```
~/.config/google-chrome/NativeMessagingHosts/com.mateclaw.browser_bridge.json
```

## Configure the bridge

Create `~/.mateclaw/bridge.yaml` (the directory is created on first run if absent):

```yaml
control_plane_url: ws://localhost:18088/api/v1/browser/edge
auth_token: <PAT>
agent_version: 0.1.0
```

Replace `<PAT>` with a Personal Access Token generated in MateClaw at `Settings → Security → Personal Access Tokens`. For production deployments behind a reverse proxy, change `ws://` to `wss://` and use your public hostname.

## Load the unpacked extension

1. Open Chrome and navigate to `chrome://extensions`.
2. Enable **Developer mode** (toggle in the top-right corner).
3. Click **Load unpacked**.
4. Select the `mateclaw-extension/dist/` directory.
5. Note the extension ID that appears under the extension card — you will need it to update the `allowed_origins` entry in the Native Messaging manifest if you did not use the install script.

## Start the Control Plane

```bash
cd mateclaw-server
mvn spring-boot:run
```

The server starts on port 18088. Wait for the log line:

```
Started MateClawApplication in ... seconds
```

The WebSocket endpoint is live at `ws://localhost:18088/api/v1/browser/edge`.

The Native Host bridge connects automatically when Chrome loads the extension (the Service Worker calls `chrome.runtime.connectNative` on startup).

## End-to-end smoke test

1. Click the MateClaw extension icon in Chrome's toolbar to open the sidepanel.
2. Click the **Ping** button.
3. Within 1 second a `pong` log entry appears in the sidepanel's log area, confirming the round trip: Sidepanel → Service Worker → Native Messaging → Native Host → WebSocket → Control Plane → back.

To verify the session is registered server-side:

```bash
curl -s -H "Authorization: Bearer <jwt_or_pat>" \
  http://localhost:18088/api/v1/browser/sessions | jq .
```

The response should be a non-empty JSON array containing at least one session object with a `session_id`, `subject`, and `connected_at` field.

## Troubleshooting

| Log message / symptom | Cause | Fix |
|---|---|---|
| `Native host has exited` (Chrome extension error) | Manifest not installed or binary path wrong | Re-run the install script; verify the `path` field in the manifest is absolute and the binary exists |
| `Failed to connect to native messaging host` | `allowed_origins` in manifest does not match the loaded extension's ID | Update the manifest's `allowed_origins` with the correct extension ID from `chrome://extensions`, then restart Chrome |
| `401 Unauthorized` on WebSocket handshake (logged by bridge) | PAT is missing, expired, or malformed | Regenerate the PAT in `Settings → Security → Personal Access Tokens` and update `~/.mateclaw/bridge.yaml` |
| `403 Forbidden` on WebSocket handshake | Reserved; not emitted in Phase 1 | — |
| Sidepanel shows no response after Ping | Service Worker may have been killed by Chrome (SW 5-minute idle timeout) | Reload the extension via `chrome://extensions → Update`, then retry |
| `session timeout (4408 close)` in bridge logs | Control Plane received no heartbeat within 30s | Check that the bridge process is running and not blocked; restart the bridge |
| `app.session_binding_mismatch` in server logs | A message arrived with a `session_id` that does not match the authenticated socket | Harmless if intermittent (race on reconnect); persistent occurrences indicate a compromised extension attempting session hijacking |
| `app.invalid_session` in server logs | A message arrived before `hello.ack` was received and `session_id` was stamped | Usually a startup race; retries automatically |

## What Phase 1 does NOT include

The following features are explicitly out of scope for Phase 1 and will land in later phases:

- **`chrome.debugger` / CDP browser control** — Phase 2. Phase 1 only proves the transport layer.
- **`action.execute` commands** — Phase 2. The wire format reserves the kind but the server does not implement it.
- **iframe support** — Phase 3.
- **Visual cursor / screen-grounding overlay** — Phase 2.
- **Offscreen Document for Service Worker kill survival** — Phase 2 (needed once CDP work is long-running enough to outlive the SW lifetime).
- **mTLS / client certificate authentication** — Phase 3 (SaaS hardening; Phase 1 uses Bearer-over-WSS).
- **SQLite outbox in the Native Host** — Phase 4 (state machine + checkpoint).
