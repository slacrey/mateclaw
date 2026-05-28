/**
 * TypeScript mirror of the Java `ActionRequest` / `ActionResult` /
 * `ActionPayload` sealed type families. Wire format documented in
 * `docs/specs/edge-protocol.md` §"action.execute" and §"action.result".
 *
 * Discriminated unions on the outer `kind` field; the TS compiler can
 * narrow `params` based on `kind` everywhere we destructure.
 */

// -----------------------------------------------------------------
// TabRef — string-or-number wire shape (matches Java TabRef sealed type)
// -----------------------------------------------------------------

export type TabRef = 'main' | 'active' | number

// -----------------------------------------------------------------
// Per-kind parameter shapes (Java equivalents under
// vip.mate.browser.edge.action.*Payload)
// -----------------------------------------------------------------

export interface NavigateParams {
  url: string
  referer?: string
  wait_for?: 'load' | 'domcontentloaded' | 'network_idle' | 'none'
}

export interface ClickParams {
  x: number
  y: number
  button?: 'left' | 'right' | 'middle'
  click_count?: number
}

export interface TypeParams {
  text: string
  focus_target?: { x: number; y: number }
}

export interface ScrollParams {
  direction: 'up' | 'down' | 'left' | 'right'
  distance_px: number
  segments?: number
}

export interface MoveMouseParams {
  x: number
  y: number
  profile?: 'natural' | 'linear'
}

export interface WaitParams {
  strategy: 'time' | 'network_idle' | 'load_state'
  duration_ms?: number
  idle_threshold_ms?: number
  load_state?: 'load' | 'domcontentloaded' | 'network_idle'
}

// -----------------------------------------------------------------
// ActionKind discriminated union
// -----------------------------------------------------------------

export type ActionKind = 'navigate' | 'click' | 'type' | 'scroll' | 'move_mouse' | 'wait'

export type ActionParams =
  | { kind: 'navigate';   params: NavigateParams }
  | { kind: 'click';      params: ClickParams }
  | { kind: 'type';       params: TypeParams }
  | { kind: 'scroll';     params: ScrollParams }
  | { kind: 'move_mouse'; params: MoveMouseParams }
  | { kind: 'wait';       params: WaitParams }

// -----------------------------------------------------------------
// ActionRequest envelope (matches Java `ActionRequest` record)
// -----------------------------------------------------------------

export type ActionRequest = {
  msg_id: string
  tab_ref: TabRef
  deadline_ms: number
} & ActionParams

// -----------------------------------------------------------------
// ActionResult sealed union (matches Java `ActionResult.Success/Failure`)
// -----------------------------------------------------------------

export interface ActionSuccess {
  ok: true
  elapsed_ms: number
  payload: Record<string, unknown>
}

export interface ActionFailure {
  ok: false
  code: ActionErrorCode
  message: string
  retryable: boolean
}

export type ActionResult = ActionSuccess | ActionFailure

/**
 * Canonical wire error codes. Match docs/specs/edge-protocol.md.
 * `HANDLER_ERROR` is a fallback when a handler throws something other
 * than an `ActionFailureError` — code wraps it but loses the typed
 * mapping (caller should treat as `retryable=true`).
 */
export type ActionErrorCode =
  | 'TIMEOUT_PAGE_LOAD'
  | 'GROUNDING_AMBIGUOUS'
  | 'NO_TARGET_TAB'
  | 'CANCELLED'
  | 'DEADLINE_EXCEEDED'
  | 'SESSION_DETACHED'
  | 'DEVTOOLS_OPEN'
  | 'UNKNOWN_KIND'
  | 'HANDLER_ERROR'
