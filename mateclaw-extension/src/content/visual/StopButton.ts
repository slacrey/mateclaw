/**
 * StopButton — bottom-center pill the user can click to abort an in-flight
 * agent task. Visual contract per research §4 + plan §C4:
 *
 *   - Bottom-center pill; container `pointer-events: none` so it never
 *     intercepts misclicks outside the pill body; button itself re-enables
 *     `pointer-events: auto`.
 *   - Slide-in: translateY(100px) → 0 + opacity 0 → 1, 300ms with the
 *     Material easing curve cubic-bezier(0.4, 0, 0.2, 1).
 *   - z-index = 2147483647 (max int), HIGHER than the glow border so the
 *     button is always clickable.
 *   - Stop icon (square in circle) + label "Stop Agent".
 *   - suppressed=true → don't mount anything (MCP mode, per research §4.3).
 *
 * The callback registered through onClick() fires on the click event. The
 * actual STOP_AGENT message dispatch lives in visual-indicator.ts (C5) so
 * the button itself stays framework-free and easily testable.
 */

const CONTAINER_ID = 'mateclaw-stop-button-container'
const BUTTON_ID = 'mateclaw-stop-button'
const STYLE_ID = 'mateclaw-stop-button-style'

const SHOW_TRANSITION_MS = 300
const HIDE_TRANSITION_MS = 300
/** prefers-reduced-motion fallback — functionally instant. (P1-8 invariant.) */
const REDUCED_TRANSITION_MS = 30
const EASING = 'cubic-bezier(0.4, 0, 0.2, 1)'

// Max int — above the glow border (also max int but appended earlier, so DOM
// order keeps stop on top).
const Z_INDEX = 2147483647

const BRAND_RGB = '61, 117, 255'

export class StopButton {
  private container: HTMLDivElement | null = null
  private button: HTMLButtonElement | null = null
  private clickHandler: (() => void) | null = null
  private hideTimer: number | null = null

  /**
   * Mount the stop button. `suppressed=true` is a hard skip for MCP mode where
   * the stop affordance lives in another surface (Claude desktop).
   */
  show(opts: { suppressed?: boolean }): void {
    if (opts.suppressed) return
    if (this.container) return // idempotent

    // Cancel any in-flight removal timer.
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer)
      this.hideTimer = null
    }

    // Inject CSS-level @media (prefers-reduced-motion: reduce) override
    // before any DOM goes in. The audit script (P1-8 invariant) requires
    // the CSS form because JS matchMedia can't override the initial
    // paint frame on a slow connection.
    ensureStyles()
    const reduced = prefersReducedMotion()
    const transitionMs = reduced ? REDUCED_TRANSITION_MS : SHOW_TRANSITION_MS

    const container = document.createElement('div')
    container.id = CONTAINER_ID
    container.style.position = 'fixed'
    container.style.left = '0'
    container.style.right = '0'
    container.style.bottom = '24px'
    container.style.display = 'flex'
    container.style.justifyContent = 'center'
    // Container is pointer-transparent so clicks outside the pill body reach
    // the page; the button itself opts back in.
    container.style.pointerEvents = 'none'
    container.style.zIndex = String(Z_INDEX)

    const btn = document.createElement('button')
    btn.id = BUTTON_ID
    btn.type = 'button'
    btn.setAttribute('aria-label', 'Stop Agent')
    btn.style.pointerEvents = 'auto'
    btn.style.zIndex = String(Z_INDEX)
    btn.style.display = 'inline-flex'
    btn.style.alignItems = 'center'
    btn.style.gap = '8px'
    btn.style.padding = '10px 18px'
    btn.style.border = 'none'
    btn.style.borderRadius = '999px'
    btn.style.background = '#FAF9F5'
    btn.style.color = '#222222'
    btn.style.fontFamily = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
    btn.style.fontSize = '14px'
    btn.style.fontWeight = '600'
    btn.style.cursor = 'pointer'
    // Layered drop-shadow per research §4.1 — investment in the "floating
    // above content" perceptual cue.
    btn.style.boxShadow =
      `0 40px 80px rgba(${BRAND_RGB}, 0.24), 0 4px 14px rgba(${BRAND_RGB}, 0.24)`
    btn.style.transition =
      `transform ${transitionMs}ms ${EASING}, ` +
      `opacity ${transitionMs}ms ${EASING}, ` +
      `background-color 150ms ease-in-out`
    if (reduced) {
      // Skip the slide-in entirely — start in the final position. The CSS
      // @media rule (injected above) is the defense-in-depth fail-safe; the
      // JS short-circuit here is the fast path.
      btn.style.transform = 'translateY(0)'
      btn.style.opacity = '1'
    } else {
      // Initial state for the slide-in fade.
      btn.style.transform = 'translateY(100px)'
      btn.style.opacity = '0'
    }

    // Hover affordance — switch the tint, do NOT change the box-shadow per
    // research §4.1 (shadow stability sells "floating").
    btn.addEventListener('mouseenter', () => {
      btn.style.background = '#F5F4F0'
    })
    btn.addEventListener('mouseleave', () => {
      btn.style.background = '#FAF9F5'
    })

    // Icon (square inside circle = universal stop glyph).
    const ns = 'http://www.w3.org/2000/svg'
    const svg = document.createElementNS(ns, 'svg')
    svg.setAttribute('width', '18')
    svg.setAttribute('height', '18')
    svg.setAttribute('viewBox', '0 0 18 18')
    svg.setAttribute('aria-hidden', 'true')
    const circle = document.createElementNS(ns, 'circle')
    circle.setAttribute('cx', '9')
    circle.setAttribute('cy', '9')
    circle.setAttribute('r', '8')
    circle.setAttribute('fill', `rgb(${BRAND_RGB})`)
    const square = document.createElementNS(ns, 'rect')
    square.setAttribute('x', '6')
    square.setAttribute('y', '6')
    square.setAttribute('width', '6')
    square.setAttribute('height', '6')
    square.setAttribute('rx', '1')
    square.setAttribute('fill', '#FFFFFF')
    svg.appendChild(circle)
    svg.appendChild(square)
    btn.appendChild(svg)

    const label = document.createElement('span')
    label.textContent = 'Stop Agent'
    btn.appendChild(label)

    btn.addEventListener('click', () => {
      // Forward to whoever set onClick(); silent if no handler yet.
      if (this.clickHandler) this.clickHandler()
    })

    container.appendChild(btn)
    document.body.appendChild(container)
    this.container = container
    this.button = btn

    // Flip to the visible state on the next frame so the transition runs.
    requestAnimationFrameSafe(() => {
      if (!this.button) return
      this.button.style.transform = 'translateY(0)'
      this.button.style.opacity = '1'
    })
  }

  hide(): void {
    if (!this.container || !this.button) return
    // Run the reverse animation, then remove.
    this.button.style.transform = 'translateY(100px)'
    this.button.style.opacity = '0'

    if (this.hideTimer !== null) clearTimeout(this.hideTimer)
    this.hideTimer = window.setTimeout(() => {
      this.hideTimer = null
      if (this.container) {
        this.container.remove()
        this.container = null
      }
      this.button = null
    }, HIDE_TRANSITION_MS)
  }

  /**
   * Register the click handler. Calling this multiple times REPLACES the
   * previous handler — there's only ever one consumer (the wiring CS).
   */
  onClick(cb: () => void): void {
    this.clickHandler = cb
  }
}

function requestAnimationFrameSafe(cb: () => void): void {
  if (typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(cb)
  } else {
    setTimeout(cb, 0)
  }
}

function prefersReducedMotion(): boolean {
  if (typeof window.matchMedia !== 'function') return false
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

/**
 * Inject the page-level stylesheet once. Carries only the
 * @media (prefers-reduced-motion: reduce) override — the slide-in transform
 * is set inline on the button, so the CSS override needs !important to win.
 *
 * Idempotent: subsequent calls no-op.
 */
function ensureStyles(): void {
  if (document.getElementById(STYLE_ID)) return
  const style = document.createElement('style')
  style.id = STYLE_ID
  style.textContent = `
@media (prefers-reduced-motion: reduce) {
  #${BUTTON_ID} {
    transition: transform ${REDUCED_TRANSITION_MS}ms linear,
                opacity ${REDUCED_TRANSITION_MS}ms linear,
                background-color 150ms ease-in-out !important;
    transform: translateY(0) !important;
    opacity: 1 !important;
  }
}
`
  document.head.appendChild(style)
}
