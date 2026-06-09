# Browser Agent STRIDE Threat Model

Version: Phase 1 v1.2 and Phase 2 security review.

Audience: InfoSec, security reviewers, release owners, and engineers approving Browser Agent before Phase 3.

This document is self-contained. It restates the Browser Agent architecture, protocol assumptions, trust boundaries, STRIDE threats, mitigations, residual risks, and external references without requiring the reader to open any other MateClaw document.

## 1. Scope

### In Scope

This threat model covers Browser Agent Phase 1 v1.2 and the planned Phase 2 capability set.

Phase 1 v1.2 is a three-process system:

- Control Plane: a Java Spring Boot monolith reachable at `localhost:18088` in local deployments or on a company intranet in managed deployments.
- Native Host: a TypeScript/Node executable, packaged as `bridge.exe` on Windows, running on the user's machine.
- Chrome Extension: a Manifest V3 extension with a service worker and Vue side panel.
- Control Plane <-> Native Host communication uses WebSocket over TLS, authenticated by an HTTP `Authorization: Bearer <JWT-or-PAT>` header during the WebSocket handshake.
- Chrome Extension <-> Native Host communication uses Chrome Native Messaging over stdin/stdout.
- The Native Host is the only component that owns the authoritative `session_id`.
- The Native Host stamps every forwarded envelope with its own `session_id` and does not trust a `session_id` supplied by the extension.
- Phase 1 only exposes a manual Ping path in the extension UI.

Phase 2 is also in scope because it changes the risk profile:

- The extension can attach to Chrome DevTools Protocol through `chrome.debugger.attach`.
- The system can dispatch browser actions such as click, type, navigation, DOM reads, and screenshots.
- The extension shows a visible automation indicator and a stop control while automation is active.
- Commands include a `tab_ref` field, resolved by the Native Host or extension into a concrete target tab.
- Tab grouping and tab selection rules are enforced so automation does not drift across tabs.
- Tool-use visual masking, such as hiding the automation banner from screenshots sent to the model, is considered part of Phase 2 design.

### Out of Scope

The following topics are intentionally out of scope for this review:

- Phase 3 multi-tenant SaaS isolation.
- Mandatory mutual TLS for every edge connection.
- Full SOP synthesis, policy-learning, or workflow-generation engines.
- Third-party website Terms of Service interpretation or enforcement.
- Legal review of automated browsing on external platforms.
- Mobile browser support.
- Non-Chrome browsers or Chromium forks.
- Enterprise device management policy rollout.

### Assumptions

- The user's machine is not compromised by an OS-level administrator, root user, kernel implant, or equivalent local malware.
- Chrome is the official Google Chrome distribution and not a fork that changes extension, debugger, or Native Messaging semantics.
- The user can correctly install the approved extension and can choose not to install it.
- Network-layer TLS is correctly implemented, current enough to resist known practical attacks, and backed by a trusted certificate chain.
- JWTs and Personal Access Tokens are not accidentally logged server-side in plaintext.
- Personal Access Tokens stored by the Native Host may be read by the same OS user, so local file permissions reduce exposure but do not eliminate it.
- The Control Plane is allowed to call configured LLM providers, and provider-specific data handling terms govern what happens after data leaves the Control Plane.
- Browser pages, page JavaScript, DOM content, and remote images are always treated as untrusted input.

## 2. Architecture Diagram

### Three-Process Topology

```text
                          Internet or intranet
                    +--------------------------------+
                    | External LLM provider APIs     |
                    | HTTPS + provider API key       |
                    +----------------+---------------+
                                     ^
                                     |
                                     | HTTPS, TLS, API-key auth
                                     |
+-------------------------+          |
| Control Plane           |----------+
| Java Spring Boot        |
| localhost:18088 or LAN  |
| Holds LLM keys, secrets |
| Stores PAT hashes       |
+------------+------------+
             ^
             |
             | WSS, TLS, Authorization: Bearer JWT or PAT
             | Handshake auth failure: HTTP 401
             | Post-upgrade auth failure target: WS 4401
             | Heartbeat timeout: WS 4408
             |
+------------+------------+
| Native Host             |
| TypeScript/Node binary  |
| Runs as user's OS user  |
| Owns session_id         |
| Stamps forwarded frames |
+------------+------------+
             ^
             |
             | Chrome Native Messaging
             | stdin/stdout JSON frames
             | Access controlled by Native Messaging manifest
             |
+------------+------------+          +-------------------------+
| Chrome Extension MV3    |<-------->| Web pages in Chrome     |
| Service worker          | Content  | DOM, cookies, sessions  |
| Side panel UI           | scripts  | untrusted page scripts  |
| Phase 2 debugger attach | isolated | high-value browser data |
+-------------------------+ world    +-------------------------+
```

### Envelope Summary

All Browser Agent protocol messages are JSON envelopes:

```json
{
  "v": 1,
  "msg_id": "uuid",
  "kind": "ping|hello|pong|action.execute|...",
  "ts": 0,
  "trace_id": "uuid",
  "session_id": "sess-...",
  "in_reply_to": "...",
  "payload": {}
}
```

Version 1 receivers must log and drop unknown `kind` values. They must not execute unknown messages and must not reinterpret unknown messages as a known action. This rule is a forward-compatibility rule and also a security rule against protocol smuggling.

During the WebSocket handshake, authentication is performed with `Authorization: Bearer <token>`. The token may be a JWT or a Personal Access Token. Handshake-time authentication failure returns HTTP 401 before the WebSocket upgrade completes. After upgrade, the close-code plan uses 4401 for authentication or revocation failures and 4408 for heartbeat timeouts.

The heartbeat interval is 10 seconds unless a `hello.ack` message overrides it. If three heartbeats fail, the receiver closes the WebSocket with 4408 and the client reconnects with exponential backoff.

In Phase 2 and later, action envelopes include a `tab_ref` field. Valid references are `"main"`, `"active"`, or an integer tab identifier. The receiver must resolve this field strictly and fail closed when the reference is ambiguous, absent, stale, or outside the allowed tab group.

## 3. Trust Boundaries

### 3.1 Browser <-> Native Host: Chrome Native Messaging stdio

The Chrome Extension and the Native Host communicate through Chrome Native Messaging. Chrome starts the Native Host according to an installed Native Messaging manifest, then exchanges length-prefixed JSON messages over stdin/stdout.

All information crossing this boundary must assume the other side is untrusted. The Native Host must not trust `session_id`, requested action kind, tab identity, or user intent simply because a message came from the extension. The extension must not assume the Native Host is the approved binary simply because `connectNative` succeeds; a malicious or stale manifest can point Chrome to another executable.

### 3.2 Native Host <-> Control Plane: WSS

The Native Host establishes a WebSocket over TLS to the Control Plane. The handshake carries a Bearer token in the HTTP `Authorization` header. The Control Plane authenticates the token before the WebSocket upgrade. After upgrade, the protocol uses JSON envelopes, heartbeat messages, close codes, and session establishment metadata.

All information crossing this boundary must assume the other side is untrusted. The Control Plane must verify the token, subject, session, message version, message kind, and payload schema. The Native Host must verify the server identity through TLS and must not continue after certificate or host validation failure.

### 3.3 Control Plane <-> LLM Provider: Provider API

The Control Plane calls external LLM providers over HTTPS using provider API keys or OAuth-derived credentials. Browser DOM, screenshots, action context, tool output, and user instructions may be sent to a provider depending on workflow configuration.

All information crossing this boundary must assume the other side is untrusted. The Control Plane must not assume provider retention, training, abuse-monitoring, or subpoena handling is identical across providers. Provider responses must also be treated as untrusted model output, not executable policy.

### 3.4 Control Plane <-> Database: Persistent State

The Control Plane stores user accounts, Personal Access Token hashes, JWT/session state, audit metadata, agent configuration, and provider configuration in the database. The database is trusted for availability and durability but not for arbitrary input correctness.

All information crossing this boundary must assume the other side is untrusted. The Control Plane must hash PATs before storage, query by approved lookup functions, validate data read back from the database, and avoid using database values as direct instructions for browser automation.

### 3.5 Chrome Extension <-> Web Page: Content Scripts and Isolated World

The extension may interact with web pages through content scripts, extension messaging, DOM reads, and later CDP-backed automation. Chrome isolates content scripts from page JavaScript in an isolated world, but the page remains adversarial input.

All information crossing this boundary must assume the other side is untrusted. The extension must treat DOM text, attributes, hidden instructions, event handlers, canvas content, and page messages as attacker-controlled. The web page must not be allowed to treat extension-origin messages as user consent.

### 3.6 Phase 2 Extension <-> chrome.debugger Protocol

In Phase 2, the extension attaches to tabs through the `chrome.debugger` API and sends Chrome DevTools Protocol commands. This gives the extension a stronger automation channel than regular DOM event dispatch and can produce user-trusted inputs, screenshots, DOM snapshots, and network-adjacent observations.

All information crossing this boundary must assume the other side is untrusted. The extension must verify tab identity, surface an automation banner with a stop affordance, restrict commands to approved actions, and treat all debugger responses as untrusted observations. The browser must be assumed to protect the API boundary, but the extension must not treat the permission as a blanket mandate to automate every tab.

## 4. STRIDE Threats

### 4.1 Browser <-> Native Host

##### T-BR-NH-S1 - Spoofing: extension sends a forged session_id

- **Asset at risk**: Control Plane session identity and cross-session isolation.
- **Vector**: a compromised or malicious extension includes another user's `session_id` in a Native Messaging envelope.
- **Impact**: commands could be attributed to the wrong browser session if the Native Host trusted the extension.
- **Likelihood**: medium, because extension compromise is plausible and `session_id` is visible in protocol messages.
- **Mitigation (current)**: the Native Host is the sole owner of `session_id` and stamps every forwarded envelope; extension-supplied `session_id` is ignored.
- **Mitigation (gap)**: add explicit metrics for discarded extension `session_id` values to identify active spoofing attempts.
- **Detection**: log a structured security event whenever the extension provides a non-empty `session_id` that differs from the Native Host session.

##### T-BR-NH-T1 - Tampering: extension mutates action payload before forwarding

- **Asset at risk**: action integrity, selected tab, and user workflow intent.
- **Vector**: a malicious extension changes `payload.selector`, typed text, URL, or `tab_ref` before the Native Host forwards the message.
- **Impact**: the agent may click a different element, disclose data from a different page, or navigate to attacker-controlled content.
- **Likelihood**: medium, because extension compromise or local unpacked-extension replacement is a realistic endpoint attack.
- **Mitigation (current)**: the Native Host stamps session identity and can validate envelope version, message kind, timestamps, and required payload shape.
- **Mitigation (gap)**: Phase 2 should add action policy validation and per-action allowlists in the Native Host before forwarding high-risk commands.
- **Detection**: correlate Control Plane planned action hashes with Native Host forwarded payload hashes and alert on mismatch.

##### T-BR-NH-R1 - Repudiation: extension-origin action cannot be tied to UI consent

- **Asset at risk**: auditability of automated browser actions.
- **Vector**: a user later denies pressing the extension Ping button or stop/start control, and the system only has a message without a local consent receipt.
- **Impact**: incident review cannot distinguish user consent, extension automation, and compromised extension activity.
- **Likelihood**: medium, especially once Phase 2 adds consequential actions beyond Ping.
- **Mitigation (current)**: messages carry `msg_id`, `trace_id`, timestamps, and Native Host session metadata.
- **Mitigation (gap)**: Phase 3 should add signed local action receipts for user-visible commands and stop/start transitions.
- **Detection**: compare side-panel UI event logs, Native Host envelope logs, and Control Plane action logs for missing or impossible transitions.

##### T-BR-NH-I1 - Information Disclosure: Native Messaging stderr or logs expose payloads

- **Asset at risk**: DOM content, screenshots, URLs, cookies inferred from page state, and PAT-adjacent metadata.
- **Vector**: the Native Host writes raw envelopes to logs, stderr, crash reports, or terminal output.
- **Impact**: local users, support bundles, or endpoint monitoring may receive sensitive browsing content.
- **Likelihood**: medium, because verbose logging is common during local troubleshooting.
- **Mitigation (current)**: protocol design does not require plaintext payload logging, and PATs are not needed inside extension-to-host payloads.
- **Mitigation (gap)**: define a redaction policy for URL query strings, DOM text, screenshots, and Bearer-token-adjacent values before Phase 2.
- **Detection**: scan logs and crash artifacts for UUID-shaped message IDs paired with URLs, HTML, screenshot metadata, or token-like strings.

##### T-BR-NH-D1 - Denial of Service: service worker kill loop destabilizes Chrome or the bridge

- **Asset at risk**: browser availability, extension reliability, and Native Host process stability.
- **Vector**: a service worker repeatedly crashes, reconnects to Native Messaging, and floods the Native Host with connect/disconnect cycles.
- **Impact**: Chrome becomes sluggish, the Native Host process churns, and legitimate automation sessions fail.
- **Likelihood**: medium, because MV3 service worker lifecycle behavior is complex and crash loops are easy to trigger accidentally.
- **Mitigation (current)**: Phase 1 is limited to Ping and has a small message surface.
- **Mitigation (gap)**: implement connection rate limits, backoff, and a circuit breaker on the Native Host side for repeated extension reconnects.
- **Detection**: alert on high Native Messaging connection churn from the same extension ID within a short window.

##### T-BR-NH-E1 - Elevation of Privilege: malicious Native Messaging manifest points to a fake bridge

- **Asset at risk**: PAT, session ownership, Control Plane access, and Chrome debugger entry point.
- **Vector**: other local software installs or overwrites the Native Messaging manifest so Chrome starts an attacker-controlled executable.
- **Impact**: the fake Native Host can capture extension messages, steal local PATs if readable, and connect to the Control Plane as the user.
- **Likelihood**: medium on unmanaged endpoints; lower on locked-down enterprise machines.
- **Mitigation (current)**: Chrome restricts Native Messaging by allowed extension origin in the manifest, and the approved bridge owns the `session_id` when correctly installed.
- **Mitigation (gap)**: installer should verify manifest path, expected binary digest, and allowed extension ID; Phase 3 should consider signed bridge attestation.
- **Detection**: health check reports should include manifest path, target binary path, binary hash, and extension ID for comparison against expected values.

### 4.2 Native Host <-> Control Plane

##### T-NH-WSS-S1 - Spoofing: attacker connects to Control Plane with a stolen PAT

- **Asset at risk**: user's Control Plane session and access to the user's LLM agent.
- **Vector**: attacker obtains plaintext PAT from local bridge configuration, backup files, clipboard history, or endpoint malware and dials `wss://<control-plane>/api/v1/browser/edge` with `Authorization: Bearer <stolen>`.
- **Impact**: full Browser Agent access for the lifetime of the PAT or until revocation.
- **Likelihood**: medium, because PATs must exist locally for unattended bridge startup.
- **Mitigation (current)**: PATs are stored hashed in the Control Plane database; plaintext lookup is limited to the active-token verification path; PATs can be revoked.
- **Mitigation (gap)**: no mTLS and no device binding in Phase 1; Phase 3 should bind PATs to `device_id` plus a stable machine fingerprint.
- **Detection**: log subject, token identifier, source IP, user agent, and device metadata on session establishment; alert when the same PAT appears from two IPs in five minutes.

##### T-NH-WSS-T1 - Tampering: man-in-the-middle modifies WSS frames

- **Asset at risk**: command integrity, browser observations, and Control Plane decisions.
- **Vector**: network attacker attempts to intercept or alter WebSocket frames between Native Host and Control Plane.
- **Impact**: actions may be changed, observations may be falsified, and the agent may make unsafe decisions.
- **Likelihood**: low when TLS validation is correct; higher on endpoints with hostile root CAs.
- **Mitigation (current)**: WSS over TLS protects confidentiality and integrity in transit.
- **Mitigation (gap)**: no application-layer message signatures; mTLS and optional envelope signatures are Phase 3 candidates.
- **Detection**: TLS failures, certificate changes, close-code spikes, and unexpected frame-schema validation failures should be monitored.

##### T-NH-WSS-R1 - Repudiation: user denies issuing an automated action and no signed receipt exists

- **Asset at risk**: non-repudiation for consequential browser actions.
- **Vector**: a user or attacker claims a click, form submission, or navigation was not authorized, while logs only show unsigned messages from the Native Host.
- **Impact**: the organization cannot prove whether the action came from user consent, LLM plan, extension compromise, or token theft.
- **Likelihood**: medium once Phase 2 actions affect real external systems.
- **Mitigation (current)**: envelopes include `msg_id`, `trace_id`, timestamps, and authenticated session context.
- **Mitigation (gap)**: Phase 3 should add signed action receipts and immutable audit storage for high-risk actions.
- **Detection**: incident review should compare action trace, UI banner state, stop-button events, tab URL, and Control Plane decision records.

##### T-NH-WSS-I1 - Information Disclosure: Bearer token leaks during handshake

- **Asset at risk**: JWT or PAT used by the Native Host to authenticate to the Control Plane.
- **Vector**: proxy logs, reverse-proxy debug mode, request dumps, packet capture on a TLS-terminating device, or accidental exception logs capture the `Authorization` header.
- **Impact**: attacker can replay the token until expiration or revocation.
- **Likelihood**: medium in managed intranet deployments with proxies; lower in local-only deployments.
- **Mitigation (current)**: Bearer auth is sent during HTTPS/WSS handshake and should be protected by TLS; PAT hashes are stored server-side.
- **Mitigation (gap)**: explicitly scrub `Authorization` at every HTTP access log and reverse proxy; prefer short-lived JWTs when possible.
- **Detection**: secret scanners should run against logs and support bundles for `Authorization: Bearer` and token-shaped values.

##### T-NH-WSS-D1 - Denial of Service: bridge reconnect spam against Control Plane

- **Asset at risk**: Control Plane WebSocket capacity, database lookups, and agent runtime availability.
- **Vector**: misconfigured or malicious bridge repeatedly reconnects with valid or invalid Bearer tokens after each close.
- **Impact**: authentication paths, WebSocket acceptors, and session registries become saturated.
- **Likelihood**: medium, because reconnect logic is expected and can be triggered by network churn or a bug.
- **Mitigation (current)**: heartbeat timeout uses 4408 and the client is expected to reconnect with exponential backoff.
- **Mitigation (gap)**: enforce server-side rate limits per subject, token, source IP, and device ID; temporarily quarantine noisy clients.
- **Detection**: alert on high connection attempts, repeated 401s, repeated 4408 closes, or many sessions from one token.

##### T-NH-WSS-E1 - Elevation of Privilege: low-privilege PAT gains broader Control Plane capability

- **Asset at risk**: user account privileges, model provider configuration, and browser automation authority.
- **Vector**: Control Plane accepts a PAT for Browser Agent but fails to scope it to Browser Agent session establishment.
- **Impact**: a bridge token may be used to access admin APIs, provider secrets, or unrelated user resources.
- **Likelihood**: low to medium depending on token scoping enforcement.
- **Mitigation (current)**: PAT authentication is a distinct lookup path and can be revoked.
- **Mitigation (gap)**: encode and enforce PAT scopes, session purpose, expiry, and device binding; deny default access to non-browser APIs.
- **Detection**: audit requests authenticated by Browser Agent PATs and flag any endpoint outside the expected WebSocket handshake path.

### 4.3 Control Plane <-> LLM Provider

##### T-CP-LLM-S1 - Spoofing: attacker impersonates an LLM provider endpoint

- **Asset at risk**: provider API keys, browser observations, and model responses that guide actions.
- **Vector**: DNS poisoning, hostile proxy, or compromised trust store redirects Control Plane provider calls to an attacker endpoint.
- **Impact**: secrets and browsing context are disclosed, and attacker-supplied responses may direct unsafe browser actions.
- **Likelihood**: low with correct TLS validation; higher on compromised hosts or networks.
- **Mitigation (current)**: provider calls use HTTPS and provider API keys.
- **Mitigation (gap)**: consider certificate pinning or allowlisted provider hosts for high-sensitivity deployments.
- **Detection**: log provider host, TLS validation failures, unusual IP ranges, and response-shape anomalies.

##### T-CP-LLM-T1 - Tampering: model output injects unsafe browser instructions

- **Asset at risk**: browser session, user data, and external websites affected by automation.
- **Vector**: provider response, prompt injection, or compromised model output requests an action outside the user's intent.
- **Impact**: the agent may navigate, click, type, or exfiltrate data in ways not requested by the user.
- **Likelihood**: medium, because LLM output is probabilistic and can be influenced by untrusted page content.
- **Mitigation (current)**: Browser Agent protocol separates action envelopes from observations and unknown kinds are dropped.
- **Mitigation (gap)**: add policy gates for high-risk actions, human confirmation for consequential operations, and structured action validation.
- **Detection**: compare model-proposed action against user objective, site allowlist, and recent page observations; alert on policy violations.

##### T-CP-LLM-R1 - Repudiation: provider interaction cannot prove exact prompt and response

- **Asset at risk**: incident reconstruction and accountability for model-driven decisions.
- **Vector**: logs store a summary but not the exact redacted prompt, model ID, provider, response, and tool decision chain.
- **Impact**: reviewers cannot determine whether the model, Control Plane, extension, or user caused an unsafe action.
- **Likelihood**: medium, especially if privacy settings reduce logging.
- **Mitigation (current)**: envelope IDs and trace IDs support correlation across components.
- **Mitigation (gap)**: define a privacy-preserving prompt/response audit record with hashing and selective redaction.
- **Detection**: run trace completeness checks that verify every action has a linked provider request, provider response, and policy decision.

##### T-CP-LLM-I1 - Information Disclosure: LLM provider trains on user DOM content

- **Asset at risk**: user DOM content, private customer data, business secrets, and session-derived context.
- **Vector**: Control Plane sends page text or structured DOM observations to a provider whose data handling policy allows retention, review, or training.
- **Impact**: sensitive website content may leave organizational control and be retained under provider-specific terms.
- **Likelihood**: medium, because provider policies vary and Browser Agent explicitly processes browser content.
- **Mitigation (current)**: provider credentials are centrally configured, and the Control Plane is the point where provider selection occurs.
- **Mitigation (gap)**: document provider data-retention settings, prefer zero-retention or enterprise agreements for browser data, and tag DOM content as sensitive.
- **Detection**: record provider, model, data category, and retention mode for each browser-observation call; audit calls involving sensitive domains.

##### T-CP-LLM-I2 - Information Disclosure: screenshot pipeline leaks PII to LLM in Phase 2

- **Asset at risk**: screenshots containing names, emails, cookies displayed in UI, financial data, health data, or internal documents.
- **Vector**: Phase 2 screenshot capture sends raw pixels to an LLM for visual reasoning.
- **Impact**: PII or regulated data may be transmitted to an external provider and retained under provider terms.
- **Likelihood**: high for broad browsing automation, because screenshots often include incidental sensitive information.
- **Mitigation (current)**: Phase 1 does not perform screenshot-based action dispatch.
- **Mitigation (gap)**: Phase 2 must add domain policy, redaction, crop-to-target, user confirmation for sensitive sites, and provider retention controls.
- **Detection**: classify screenshot requests by domain and workflow, sample redacted audit thumbnails locally, and alert on screenshots from sensitive domains.

##### T-CP-LLM-D1 - Denial of Service: provider latency or quota exhaustion stalls browser sessions

- **Asset at risk**: agent availability, browser session continuity, and user trust in stop controls.
- **Vector**: provider outage, throttling, high latency, or quota exhaustion causes actions to hang while debugger attachment remains active.
- **Impact**: automation appears stuck, user cannot complete workflow, and pending actions may time out in inconsistent states.
- **Likelihood**: medium, because external providers have quotas and incidents.
- **Mitigation (current)**: Control Plane can choose among configured providers and handles provider calls centrally.
- **Mitigation (gap)**: define per-action timeouts, detach debugger on provider stall, and fail closed with visible user feedback.
- **Detection**: monitor provider latency, quota errors, per-session pending-action age, and debugger attachment duration.

##### T-CP-LLM-E1 - Elevation of Privilege: prompt injection causes tool use beyond browser intent

- **Asset at risk**: Control Plane tools, provider secrets, and user resources outside the current browser workflow.
- **Vector**: malicious webpage text instructs the LLM to call higher-privilege tools, reveal secrets, or change system settings.
- **Impact**: a webpage can indirectly influence the agent to act outside the user's browsing task.
- **Likelihood**: medium to high for browser agents because pages are untrusted and prompt injection is a known agentic risk.
- **Mitigation (current)**: page content is observation data, not a trusted system instruction.
- **Mitigation (gap)**: enforce tool policy by origin, action type, and user approval; isolate untrusted page text in a data channel with clear model instructions.
- **Detection**: flag model requests that cite page text as authority for privileged operations or attempt to access secrets unrelated to the task.

### 4.4 Control Plane <-> Database

##### T-CP-DB-S1 - Spoofing: attacker inserts or activates a PAT record

- **Asset at risk**: user authentication state and Browser Agent session establishment.
- **Vector**: SQL injection, compromised DB credentials, or admin console abuse creates an active PAT hash for an attacker-controlled token.
- **Impact**: attacker can authenticate as the target user without stealing an existing PAT.
- **Likelihood**: low if DB access is protected, but impact is high.
- **Mitigation (current)**: PATs are stored as hashes, not plaintext, and authentication must match the active hash.
- **Mitigation (gap)**: add admin approval or alerting for PAT creation, rotation, and activation events.
- **Detection**: alert on new PAT creation outside expected UI flows, bulk PAT changes, or PAT activation after long dormancy.

##### T-CP-DB-T1 - Tampering: database values alter browser automation policy

- **Asset at risk**: policy enforcement, allowed domains, provider selection, and user permissions.
- **Vector**: attacker modifies database rows controlling feature flags, token status, provider configuration, or action policy.
- **Impact**: Browser Agent may execute actions on disallowed domains or use a provider with weaker data handling.
- **Likelihood**: low to medium depending on DB access controls.
- **Mitigation (current)**: Control Plane centralizes policy interpretation and can validate records before use.
- **Mitigation (gap)**: add signed or versioned policy records for high-risk browser automation settings.
- **Detection**: audit policy-row changes with actor, before/after values, source IP, and deployment context.

##### T-CP-DB-R1 - Repudiation: database audit rows can be edited after incident

- **Asset at risk**: investigation integrity and compliance evidence.
- **Vector**: privileged operator or attacker edits or deletes audit records after unsafe automation.
- **Impact**: security review cannot trust the event timeline.
- **Likelihood**: medium for systems where application and audit data share one mutable database.
- **Mitigation (current)**: trace IDs and message IDs can correlate events across runtime logs.
- **Mitigation (gap)**: Phase 3 should write high-risk action audit events to an immutable append-only store.
- **Detection**: compare database audit sequence numbers with application logs and alert on gaps, rewrites, or timestamp inversions.

##### T-CP-DB-I1 - Information Disclosure: PAT hashes and provider secrets leak from database backup

- **Asset at risk**: PAT hashes, provider configuration, OAuth refresh data, and user metadata.
- **Vector**: database backup, snapshot, support dump, or developer copy is exposed.
- **Impact**: attackers may attempt offline token cracking, target users, or recover provider credentials if encrypted improperly.
- **Likelihood**: medium, because backups are common and often copied outside production.
- **Mitigation (current)**: PATs are hashed before storage.
- **Mitigation (gap)**: encrypt provider secrets with a key outside the database, rotate backup access, and document backup retention.
- **Detection**: monitor backup access, restore events, unusual export size, and secret-scanner hits in shared locations.

##### T-CP-DB-D1 - Denial of Service: PAT lookup or session registry exhausts database resources

- **Asset at risk**: authentication availability and Control Plane database capacity.
- **Vector**: attacker sends many invalid Bearer tokens, forcing repeated hash checks or database lookups.
- **Impact**: legitimate bridges cannot establish sessions, and other Control Plane functions slow down.
- **Likelihood**: medium for exposed intranet endpoints; low for local-only deployments.
- **Mitigation (current)**: handshake failures return HTTP 401 before WebSocket upgrade.
- **Mitigation (gap)**: add negative-token cache, per-IP rate limit, and exponential penalty for repeated invalid tokens.
- **Detection**: alert on high 401 volume, repeated failed token lookup, and authentication query latency.

##### T-CP-DB-E1 - Elevation of Privilege: token scope or role is interpreted too broadly

- **Asset at risk**: role-based access control and administrative capabilities.
- **Vector**: database role, token scope, or feature flag is missing, null, stale, or parsed permissively by Control Plane code.
- **Impact**: Browser Agent session token gains access to admin settings, model secrets, or other users' data.
- **Likelihood**: low to medium, especially during schema migrations or backward compatibility changes.
- **Mitigation (current)**: PATs are distinct records and can be revoked.
- **Mitigation (gap)**: fail closed on missing scope fields and add migration tests for legacy token records.
- **Detection**: query audit logs for Browser Agent token identities accessing non-browser resources.

### 4.5 Chrome Extension <-> Web Page

##### T-EXT-WEB-S1 - Spoofing: web page pretends to be the extension or automation UI

- **Asset at risk**: user consent, stop control, and trust in automation state.
- **Vector**: a malicious site renders fake banners, fake stop buttons, or fake extension instructions inside the page.
- **Impact**: user may believe automation is stopped or approved when the real extension state differs.
- **Likelihood**: medium, because websites can imitate UI visually.
- **Mitigation (current)**: extension UI lives in Chrome-controlled extension surfaces rather than inside page DOM.
- **Mitigation (gap)**: Phase 2 banner should be extension-controlled and visually distinguishable from page content.
- **Detection**: user reports and screenshot review can identify page-rendered controls that mimic extension UI.

##### T-EXT-WEB-T1 - Tampering: untrusted MouseEvent dispatch causes action/result mismatch

- **Asset at risk**: action integrity and user-trusted browser input.
- **Vector**: a compromised extension or shortcut implementation dispatches DOM `MouseEvent` objects; page code rejects them because `isTrusted` is false or handles them differently from real user input.
- **Impact**: the agent may think a click succeeded when the page ignored it, or page scripts may tamper with the result path.
- **Likelihood**: high if DOM events are used for real automation; low if CDP-only dispatch is enforced.
- **Mitigation (current)**: Phase 1 does not perform click dispatch.
- **Mitigation (gap)**: Phase 2 must use CDP-only input dispatch for click and keyboard actions, then verify post-action state.
- **Detection**: detect action success by browser-observed state changes rather than by event-dispatch return values.

##### T-EXT-WEB-R1 - Repudiation: page denies receiving an automated action

- **Asset at risk**: auditability of interactions with third-party sites.
- **Vector**: a site claims no action happened or that automation violated policy, while Browser Agent lacks exact page-state evidence.
- **Impact**: operator cannot prove what was displayed, clicked, or submitted.
- **Likelihood**: medium for workflows touching external business systems.
- **Mitigation (current)**: trace IDs can tie planned actions to protocol messages.
- **Mitigation (gap)**: Phase 2 should store privacy-redacted pre/post action snapshots for high-risk actions when policy allows.
- **Detection**: compare action traces with local browser history, network timing, and redacted pre/post observations.

##### T-EXT-WEB-I1 - Information Disclosure: content scripts read more DOM than needed

- **Asset at risk**: cookies inferred from UI, PII, internal documents, customer records, and visible page content.
- **Vector**: content scripts collect full DOM, hidden fields, or broad page text when only a small element is needed.
- **Impact**: unnecessary data may be sent to the Native Host, Control Plane, or LLM provider.
- **Likelihood**: medium to high without strict minimization.
- **Mitigation (current)**: Phase 1 does not read page DOM for action dispatch.
- **Mitigation (gap)**: Phase 2 should implement least-data collection, target-crop screenshots, domain controls, and sensitive-field masking.
- **Detection**: audit observation payload size, DOM node count, field names, and sensitive-domain usage.

##### T-EXT-WEB-D1 - Denial of Service: hostile page exhausts extension or debugger processing

- **Asset at risk**: Chrome tab availability, extension service worker stability, and user workflow completion.
- **Vector**: a page creates huge DOMs, infinite mutation loops, modal storms, or expensive canvas updates that trigger repeated observations.
- **Impact**: extension CPU spikes, debugger commands time out, and automation stalls.
- **Likelihood**: medium for arbitrary web browsing.
- **Mitigation (current)**: Phase 1 has no content-script observation loop.
- **Mitigation (gap)**: Phase 2 should cap DOM extraction size, screenshot frequency, mutation observation, and per-tab processing time.
- **Detection**: track per-tab CPU time, observation payload size, debugger timeout counts, and service worker restart counts.

##### T-EXT-WEB-E1 - Elevation of Privilege: page script influences extension through message confusion

- **Asset at risk**: extension privileges and Native Host connection.
- **Vector**: page uses `postMessage`, DOM attributes, or injected content to trick content scripts into forwarding privileged commands.
- **Impact**: untrusted web content may indirectly request actions, read data, or influence Control Plane commands.
- **Likelihood**: medium if message origin and schema checks are weak.
- **Mitigation (current)**: content scripts run in an isolated world, reducing direct page access to extension internals.
- **Mitigation (gap)**: validate message origin, message source, schema, action type, and active user gesture before forwarding.
- **Detection**: log rejected page-origin messages and alert on repeated attempts from one origin.

### 4.6 Phase 2 Extension <-> chrome.debugger Protocol

##### T-EXT-CDP-S1 - Spoofing: action targets the wrong tab through ambiguous tab_ref

- **Asset at risk**: cookies, sessions, and DOM data in unintended tabs.
- **Vector**: an action envelope uses `"active"` or a stale integer tab ID after the user switches tabs.
- **Impact**: Browser Agent reads or acts on a different authenticated site than intended.
- **Likelihood**: medium because tab focus changes frequently.
- **Mitigation (current)**: Phase 2 design introduces explicit `tab_ref` values.
- **Mitigation (gap)**: enforce strict tab resolution, tab-group membership, URL checks, and fail-closed semantics for ambiguity.
- **Detection**: log resolved tab ID, URL origin, group ID, and reason for each action; alert on stale or ambiguous resolution failures.

##### T-EXT-CDP-T1 - Tampering: debugger response is trusted without validation

- **Asset at risk**: model observations, action success checks, and browser state decisions.
- **Vector**: extension bug, compromised extension, or unexpected CDP response shape causes false DOM or screenshot observations.
- **Impact**: Control Plane may make unsafe decisions based on incorrect browser state.
- **Likelihood**: low to medium.
- **Mitigation (current)**: Phase 1 has no debugger channel.
- **Mitigation (gap)**: Phase 2 should validate CDP response schema, tab identity, frame identity, and navigation epoch.
- **Detection**: compare independent signals such as URL, frame ID, screenshot timestamp, and DOM snapshot timestamp.

##### T-EXT-CDP-R1 - Repudiation: who-clicked-what audit log gap

- **Asset at risk**: accountability for CDP-backed click, type, and navigation actions.
- **Vector**: an action occurs through `chrome.debugger`, but logs only say `action.execute` without element coordinates, URL, tab, actor, and user-visible automation state.
- **Impact**: incident responders cannot reconstruct who clicked what, where, and under which authorization.
- **Likelihood**: high unless explicitly designed.
- **Mitigation (current)**: protocol envelopes include `msg_id`, `trace_id`, and timestamps.
- **Mitigation (gap)**: Phase 2 should log actor, tab, origin, selector or coordinate, action type, pre/post URL, and banner state; Phase 3 should sign receipts.
- **Detection**: trace completeness job flags CDP actions missing tab URL, element target, or linked user/workflow intent.

##### T-EXT-CDP-I1 - Information Disclosure: automation banner appears in screenshots and confuses or leaks state

- **Asset at risk**: screenshot content, user awareness state, and LLM visual interpretation.
- **Vector**: Phase 2 screenshots include the extension's automation indicator or stop control.
- **Impact**: provider receives operational metadata, and the model may misinterpret the banner as page content.
- **Likelihood**: medium, because screenshots capture visible browser viewport by default.
- **Mitigation (current)**: Phase 1 does not send screenshots to LLM providers.
- **Mitigation (gap)**: Phase 2 should implement hide-for-tool-use masking for extension-owned indicators while keeping them visible to the user.
- **Detection**: visual QA and screenshot pipeline tests should detect banner pixels in captured tool-use images.

##### T-EXT-CDP-D1 - Denial of Service: debugger attach remains active and blocks normal browsing

- **Asset at risk**: user browsing availability and control over Chrome.
- **Vector**: extension attaches debugger to a tab and fails to detach after workflow completion, error, provider timeout, or stop button.
- **Impact**: user sees persistent debugger state, automation cannot proceed cleanly, and Chrome may restrict other debugging tools.
- **Likelihood**: medium during Phase 2 implementation.
- **Mitigation (current)**: Phase 1 has no debugger attach.
- **Mitigation (gap)**: Phase 2 should use scoped attach lifetimes, finalizers, stop-button detach, and timeout detach.
- **Detection**: monitor active debugger sessions and alert if any tab remains attached longer than the workflow timeout.

##### T-EXT-CDP-E1 - Elevation of Privilege: compromised extension abuses `<all_urls>` and `debugger`

- **Asset at risk**: all browser tabs, cookies, authenticated sessions, DOM content, screenshots, and user actions.
- **Vector**: attacker compromises the extension package or update path and uses broad host permissions plus debugger permission to automate arbitrary sites.
- **Impact**: full browser compromise within Chrome extension permission limits.
- **Likelihood**: low if distribution is controlled; high impact if it occurs.
- **Mitigation (current)**: MV3 limits some extension behavior, and Phase 1 has only Ping functionality.
- **Mitigation (gap)**: minimize host permissions where practical, require visible automation banner, enforce domain policy, and lock update/signing process.
- **Detection**: monitor extension version, manifest permissions, unexpected debugger attaches, and actions outside approved workflows.

## 5. Threat Matrix Summary

| Threat ID | Likelihood | Impact | Phase | Status |
|---|---:|---:|---:|---|
| T-BR-NH-S1 | M | H | 1 | mitigated |
| T-BR-NH-T1 | M | H | 2 | partial |
| T-BR-NH-R1 | M | M | 2 | partial |
| T-BR-NH-I1 | M | H | 2 | partial |
| T-BR-NH-D1 | M | M | 1 | partial |
| T-BR-NH-E1 | M | H | 1 | open |
| T-NH-WSS-S1 | M | H | 1 | partial |
| T-NH-WSS-T1 | L | H | 1 | mitigated |
| T-NH-WSS-R1 | M | H | 2 | partial |
| T-NH-WSS-I1 | M | H | 1 | partial |
| T-NH-WSS-D1 | M | M | 1 | partial |
| T-NH-WSS-E1 | M | H | 1 | partial |
| T-CP-LLM-S1 | L | H | 1 | partial |
| T-CP-LLM-T1 | M | H | 2 | partial |
| T-CP-LLM-R1 | M | M | 2 | partial |
| T-CP-LLM-I1 | M | H | 2 | partial |
| T-CP-LLM-I2 | H | H | 2 | open |
| T-CP-LLM-D1 | M | M | 2 | partial |
| T-CP-LLM-E1 | H | H | 2 | partial |
| T-CP-DB-S1 | L | H | 1 | partial |
| T-CP-DB-T1 | M | H | 1 | partial |
| T-CP-DB-R1 | M | H | 3 | open |
| T-CP-DB-I1 | M | H | 1 | partial |
| T-CP-DB-D1 | M | M | 1 | partial |
| T-CP-DB-E1 | M | H | 1 | partial |
| T-EXT-WEB-S1 | M | M | 2 | partial |
| T-EXT-WEB-T1 | H | H | 2 | partial |
| T-EXT-WEB-R1 | M | M | 2 | open |
| T-EXT-WEB-I1 | H | H | 2 | open |
| T-EXT-WEB-D1 | M | M | 2 | partial |
| T-EXT-WEB-E1 | M | H | 2 | partial |
| T-EXT-CDP-S1 | M | H | 2 | partial |
| T-EXT-CDP-T1 | M | H | 2 | partial |
| T-EXT-CDP-R1 | H | H | 2 | partial |
| T-EXT-CDP-I1 | M | M | 2 | partial |
| T-EXT-CDP-D1 | M | M | 2 | partial |
| T-EXT-CDP-E1 | L | H | 2 | partial |

Status definitions:

- **mitigated**: Phase 1 design directly addresses the threat with an implemented or required control.
- **partial**: a meaningful control exists, but at least one important mitigation remains for Phase 2 or Phase 3.
- **open**: the threat requires a new control before the relevant phase should be considered production-ready.

## 6. Mitigations & Roadmap

### Phase 1: Current Controls

| Control | Threats Reduced | Notes |
|---|---|---|
| Native Host owns and stamps `session_id` | T-BR-NH-S1 | Extension-provided `session_id` is not authoritative. |
| Bearer authentication during WebSocket handshake | T-NH-WSS-S1, T-NH-WSS-E1 | Handshake failure returns HTTP 401 before upgrade. |
| PATs hashed in Control Plane database | T-NH-WSS-S1, T-CP-DB-I1 | Plaintext PAT is only available at creation and in local bridge config. |
| PAT revocation support | T-NH-WSS-S1 | Revocation limits stolen-token lifetime after detection. |
| WSS over TLS | T-NH-WSS-T1, T-NH-WSS-I1 | Relies on healthy certificate validation and trusted roots. |
| Close-code taxonomy | T-NH-WSS-D1 | 4408 heartbeat timeout is part of current heartbeat behavior; 4401 post-upgrade auth close remains a Phase 3 completion item. |
| 10 second heartbeat with 3 missed responses | T-NH-WSS-D1 | Detects dead sessions and avoids silent hangs. |
| Client exponential backoff expectation | T-NH-WSS-D1 | Needs server-side enforcement to protect against buggy clients. |
| Forward-compatible unknown-kind drop | T-CP-LLM-T1, protocol smuggling risks | v1 receivers log and drop unknown kinds instead of executing them. |
| Phase 1 extension limited to manual Ping | T-EXT-WEB-T1, T-EXT-CDP-E1 | Keeps early release blast radius small. |

Phase 1 release criteria:

- Do not accept a Browser Agent WebSocket session without a valid JWT or active PAT.
- Do not allow the extension to choose or override `session_id`.
- Do not execute unknown protocol `kind` values.
- Do not log Bearer tokens, PAT plaintext, or raw browser payloads.
- Do make session establishment observable with subject, token identifier, source, and device metadata when available.

### Phase 2: Required Before Browser Actions

| Planned Control | Threats Reduced | Required Behavior |
|---|---|---|
| CDP-only input dispatch | T-EXT-WEB-T1 | Click and keyboard actions use debugger-backed input, not DOM `MouseEvent` dispatch. |
| Post-action state verification | T-EXT-WEB-T1, T-EXT-CDP-T1 | Success is based on observed page state, not send-command success alone. |
| Visual automation indicator banner | T-EXT-WEB-S1, T-EXT-CDP-E1 | User can see automation and stop it at any time. |
| Stop control with forced detach | T-EXT-CDP-D1 | Stop immediately cancels pending actions and detaches debugger. |
| Strict `tab_ref` resolution | T-EXT-CDP-S1 | `"main"`, `"active"`, and integer refs must resolve unambiguously. |
| Tab group enforcement | T-EXT-CDP-S1 | Automation cannot drift into unrelated tabs. |
| Domain and origin policy | T-EXT-WEB-I1, T-EXT-CDP-E1 | Sensitive domains require deny, prompt, or restricted observation mode. |
| Least-data DOM extraction | T-EXT-WEB-I1 | Collect target element context instead of full page whenever possible. |
| Screenshot redaction and crop-to-target | T-CP-LLM-I2 | Avoid sending full viewport pixels when a small region is sufficient. |
| Hide-for-tool-use banner masking | T-EXT-CDP-I1 | Banner remains visible to user but is not included in model-bound screenshots. |
| Action audit schema | T-EXT-CDP-R1, T-NH-WSS-R1 | Store actor, trace, tab, origin, target, action, and pre/post state. |
| Provider data handling labels | T-CP-LLM-I1, T-CP-LLM-I2 | Every model call records provider retention mode and data category. |

Phase 2 release criteria:

- Every browser action has a resolved tab identity and an action policy decision.
- Every click and keyboard action is CDP-backed and followed by state verification.
- Every active automation session shows a user-visible indicator and stop affordance.
- Screenshot and DOM observations are minimized before leaving the browser.
- Browser observations sent to LLM providers are classified by sensitivity and provider retention mode.
- Debugger attach always has a bounded lifetime and a detach path on stop, timeout, error, and completion.

### Phase 3: Planned Hardening

| Planned Control | Threats Reduced | Notes |
|---|---|---|
| mTLS between Native Host and Control Plane | T-NH-WSS-S1, T-NH-WSS-T1 | Adds device/client authentication beyond Bearer tokens. |
| PAT device binding | T-NH-WSS-S1 | Bind PAT to `device_id` and machine fingerprint. |
| Signed action receipts | T-BR-NH-R1, T-NH-WSS-R1, T-EXT-CDP-R1 | Provides non-repudiation for high-risk actions. |
| Immutable audit store | T-CP-DB-R1 | Prevents post-incident log rewriting. |
| Post-upgrade revocation check and 4401 close | T-NH-WSS-S1 | Closes active sessions after token revocation or subject disable. |
| Signed bridge attestation | T-BR-NH-E1 | Helps detect fake Native Host binaries. |
| Envelope signatures for high-risk commands | T-NH-WSS-T1, T-BR-NH-T1 | Adds application-layer integrity on top of TLS. |
| Admin anomaly detection | T-CP-DB-S1, T-CP-DB-T1 | Alerts on token, provider, and policy changes. |

Phase 3 release criteria:

- A stolen PAT alone should not be sufficient to establish a long-lived browser session from a new device.
- A high-risk browser action should have a signed receipt tying together user intent, model decision, tab, target, and timestamp.
- Security reviewers should be able to reconstruct action history from immutable records.
- Revoked tokens should be terminated after upgrade, not only rejected at the next handshake.

## 7. Residual Risks Acceptance

This section records risks that remain acceptable after Phase 2, assuming the Phase 2 controls listed above are implemented. The phrase "we accept" is intentional: these are not forgotten risks. They are conscious tradeoffs for a local browser automation system.

### OS-Level Compromise

We accept that if the same OS user, root user, administrator, kernel malware, or endpoint implant compromises the user's machine, the bridge, extension, Chrome profile, local PAT storage, and local browser sessions are all exposed.

Reason:

- Browser Agent is explicitly designed to operate inside the user's real Chrome profile.
- The Native Host runs as the user.
- Chrome cookies and business sessions already exist on the endpoint.
- A root-level attacker can read files, replace binaries, inspect memory, control Chrome, and intercept local IPC.
- Defending against full endpoint compromise requires enterprise endpoint security, disk encryption, MDM, EDR, and OS hardening outside this feature's boundary.

Accepted residual exposure:

- Attacker may steal the bridge PAT before Phase 3 device binding.
- Attacker may replace the Native Host binary or manifest.
- Attacker may install a malicious extension if local policy allows it.
- Attacker may read or modify browser data directly without using Browser Agent.

Compensating controls:

- Keep PAT revocation easy and visible.
- Surface bridge health and manifest diagnostics.
- Document expected manifest location, binary path, and extension ID.
- Prefer enterprise extension allowlisting for managed fleets.
- Add device binding and signed bridge attestation in Phase 3.

### Wrong Extension Installed by User

We accept that a user may be phished into installing the wrong extension, a lookalike unpacked extension, or a malicious extension with similar branding.

Reason:

- Browser Agent cannot fully control what a user installs into their own browser outside managed-device policy.
- Chrome Web Store review, extension signing, enterprise policy, and user education are the main controls for extension provenance.
- A local-only feature must remain installable by users without assuming every machine is centrally managed.

Accepted residual exposure:

- A malicious extension may impersonate UI, request broad permissions, or attempt to call a fake Native Host.
- If a malicious Native Messaging manifest is also installed, the wrong extension may communicate with attacker-controlled local code.
- The approved Control Plane should still reject sessions without valid Bearer authentication.

Compensating controls:

- Publish the approved extension ID in installation instructions.
- Include extension ID and version in bridge health diagnostics.
- Warn users when the Native Host sees unexpected extension IDs.
- Encourage managed Chrome policies for high-risk deployments.

### LLM Provider Data Handling

We accept that LLM provider retention, review, training, abuse monitoring, regional processing, and legal disclosure policies are external factors once data is sent to that provider.

Reason:

- Browser Agent relies on model providers for reasoning.
- Provider terms vary by product tier, account type, region, model, and enterprise agreement.
- The Control Plane can minimize, classify, and route data, but it cannot unilaterally change a provider's policy.

Accepted residual exposure:

- DOM text or screenshots may be retained by a provider according to the configured account policy.
- Provider-side abuse monitoring may involve automated or human review depending on provider terms.
- Legal or compliance obligations may require provider-side disclosure.

Compensating controls:

- Prefer provider modes that do not train on customer inputs or outputs.
- Record provider retention mode for each browser-observation call.
- Use redaction, cropping, and least-data extraction before sending browser data.
- Give administrators a way to deny model calls for sensitive domains.

### Prompt Injection From Web Content

We accept that prompt injection cannot be eliminated completely when an agent reads arbitrary web pages.

Reason:

- Browser pages are untrusted and may contain hidden, visible, or encoded instructions.
- LLMs can be influenced by adversarial content despite system prompts and model training.
- Browser agents combine untrusted reading with action capability, which makes prompt injection a core residual risk.

Accepted residual exposure:

- A page may attempt to convince the model to ignore user instructions.
- A page may embed instructions in hidden DOM, images, comments, or accessibility text.
- A page may try to cause data disclosure or cross-site action through model confusion.

Compensating controls:

- Treat page content as data, not authority.
- Require policy gates for consequential actions.
- Add user confirmation where impact is high.
- Restrict tools and domains available to browser-originated tasks.
- Log model decisions and action policy outcomes for review.

### External Website Behavior

We accept that third-party websites may change DOM structure, event handling, bot detection, rate limits, and visual UI without notice.

Reason:

- Browser Agent interacts with live websites outside the organization's control.
- CDP-backed input improves fidelity but does not guarantee that a website accepts or permits automation.
- Some websites may treat automation as abuse even when the user owns the account.

Accepted residual exposure:

- Actions may fail due to DOM drift or anti-automation logic.
- Websites may detect debugger attachment or automation timing.
- Websites may display fake UI or malicious content to influence the agent.

Compensating controls:

- Use post-action verification.
- Keep user-visible stop control active.
- Store redacted pre/post state for high-risk actions when allowed.
- Keep Terms of Service analysis outside this technical threat model.

### Local Availability and Usability

We accept that local browser automation may occasionally degrade browser responsiveness or require restarting the bridge, extension, or Chrome.

Reason:

- MV3 service workers are lifecycle-managed by Chrome.
- Debugger attachment, screenshotting, DOM extraction, and provider round trips can stall.
- Local machines vary widely in CPU, memory, extension load, and network reliability.

Accepted residual exposure:

- The bridge may reconnect repeatedly under poor network conditions.
- The extension service worker may restart during a workflow.
- Debugger attach may fail or require cleanup.

Compensating controls:

- Enforce timeouts and detach finalizers.
- Use exponential backoff and server-side rate limiting.
- Make stop control reliable and visible.
- Provide diagnostics for bridge, extension, and Control Plane connectivity.

## 8. References

- Chrome Extensions security guidance: https://developer.chrome.com/docs/extensions/develop/security-privacy/stay-secure
- Chrome Native Messaging documentation: https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging
- Chrome extension content scripts and isolated worlds: https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts
- Chrome `debugger` API reference: https://developer.chrome.com/docs/extensions/reference/api/debugger
- OWASP Top 10: https://owasp.org/www-project-top-ten/
- MITRE ATT&CK T1176, Software Extensions: https://attack.mitre.org/techniques/T1176/
- OpenAI Guardrails, prompt injection detection check: https://openai.github.io/openai-guardrails-python/ref/checks/prompt_injection_detection/
- OpenAI API prompt engineering guide: https://developers.openai.com/api/docs/guides/prompt-engineering
- Anthropic Claude API docs, Mitigate jailbreaks and prompt injections: https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/mitigate-jailbreaks
- Anthropic research, Mitigating the risk of prompt injections in browser use: https://www.anthropic.com/research/prompt-injection-defenses
