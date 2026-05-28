/**
 * PhantomCursor — a fixed-position overlay arrow that follows the synthesized
 * pointer position driven by the SW's VisualCoordinator. It is purely visual:
 * the real `Input.dispatchMouseEvent` CDP calls are issued separately by the
 * action layer. The phantom exists so the user perceives motion BEFORE the
 * click actually lands (research §2.3 timing: cursor arrives ~200ms early).
 *
 * Visual contract:
 *   - Container <div id="mateclaw-phantom-cursor"> fixed-positioned at (0,0)
 *     with translate3d(x,y,0) carrying the actual position. GPU compositing
 *     via will-change + translate3d.
 *   - z-index 2147483646 (one below the max, reserved for the stop button).
 *   - pointer-events: none — never intercepts user input.
 *   - Two inline SVG arrows (data-theme="plain" + data-theme="styled"); only
 *     one display:"" at any time. setStyle() toggles between them.
 *   - Transition: transform 180ms cubic-bezier(0.2,0,0,1). Under
 *     prefers-reduced-motion: reduce, the duration collapses to 0ms (P1-8).
 *
 * move(x, y) returns a Promise that resolves when the transition is "done":
 *   - via transitionend (real browsers, foreground tab), OR
 *   - via a 220ms fallback timer (happy-dom tests, background tabs where
 *     transitionend doesn't fire). The first one wins.
 *
 * This module is consumed by visual-indicator.ts (the content script entry).
 * It does NOT use any chrome.* API — keeping it framework-free makes it easy
 * to unit-test in happy-dom.
 */

const CONTAINER_ID = 'mateclaw-phantom-cursor'

// 180ms easing matches research §2.4 ("拟人 cursor smooth"). Reduced motion
// drops to 0 so users with vestibular sensitivity see instant teleport.
const NORMAL_TRANSITION_MS = 180
const REDUCED_TRANSITION_MS = 0
const TRANSITION_FALLBACK_MS = 220 // transition + 40ms safety per research §2.4

// z-index hierarchy: phantom cursor sits just below the stop button so the
// user can always click stop even when the cursor is under their pointer.
const Z_INDEX = 2147483646

// 20x26 viewBox per research §2.1. Two themes share geometry so swapping is
// purely a visibility toggle, not a layout reflow.
const SVG_VIEWBOX = '0 0 20 26'
const SVG_WIDTH = 20
const SVG_HEIGHT = 26

// Arrow path: tip at (0,0), tail bending down-right. Roughly Apple-HIG shape
// but distinct enough that a careful observer can tell "this isn't my OS cursor".
const ARROW_PATH = 'M0 0 L0 18 L5 13.5 L8.5 22 L11.5 20.7 L8 12.5 L14 12.5 Z'

export type PhantomCursorTheme = 'plain' | 'styled'

export class PhantomCursor {
  private container: HTMLDivElement | null = null
  private plainSvg: SVGElement | null = null
  private styledSvg: SVGElement | null = null
  private currentTheme: PhantomCursorTheme = 'styled'

  /**
   * Lazy-create the container and attach to <body> at (x,y). No-op if already
   * mounted (caller can drive move() instead).
   */
  mount(x: number, y: number): void {
    if (this.container) {
      // Already mounted — just reposition. Caller likely meant move(), but
      // it's nicer to be tolerant than throw.
      this.container.style.transform = `translate3d(${x}px, ${y}px, 0)`
      return
    }

    // Inject the global stylesheet ONCE per page so the CSS-level
    // @media (prefers-reduced-motion: reduce) override is in place even
    // before any JS check fires. The audit script (#7 invariant) greps
    // for this exact CSS form across visual code — JS matchMedia alone
    // is not sufficient because it can't override the initial paint
    // before scripts run on a slow connection.
    ensureStyles()

    const reduced = prefersReducedMotion()
    const transitionMs = reduced ? REDUCED_TRANSITION_MS : NORMAL_TRANSITION_MS

    const div = document.createElement('div')
    div.id = CONTAINER_ID
    div.style.position = 'fixed'
    div.style.top = '0px'
    div.style.left = '0px'
    div.style.pointerEvents = 'none'
    div.style.zIndex = String(Z_INDEX)
    div.style.width = `${SVG_WIDTH}px`
    div.style.height = `${SVG_HEIGHT}px`
    // transform-origin defaults to (50%, 50%) which would put the tip off-target.
    // Anchor at top-left so the tip of the arrow IS the (x,y) coordinate.
    div.style.transformOrigin = '0 0'
    div.style.transform = `translate3d(${x}px, ${y}px, 0)`
    div.style.transition = `transform ${transitionMs}ms cubic-bezier(0.2, 0, 0, 1)`
    div.style.willChange = 'transform'

    // Two themed SVGs share the same arrow path; only the fill/stroke differ.
    // We carry data-theme attrs so tests can identify them by selector.
    const plain = makeArrowSvg('plain')
    const styled = makeArrowSvg('styled')
    div.appendChild(plain)
    div.appendChild(styled)

    document.body.appendChild(div)
    this.container = div
    this.plainSvg = plain
    this.styledSvg = styled

    // Default theme = styled (the brand-colored, glowing one). Tests can flip
    // it via setStyle() — and the wiring CS may pick a theme later based on
    // accessibility preferences.
    this.setStyle(this.currentTheme)
  }

  /**
   * Move the cursor to (x,y) and return a Promise that resolves once the CSS
   * transition reports done (via transitionend OR a 220ms fallback timer,
   * whichever first). If the cursor isn't mounted, resolves immediately —
   * callers can pipeline await without checking mount state.
   */
  move(x: number, y: number): Promise<void> {
    const el = this.container
    if (!el) return Promise.resolve()

    el.style.transform = `translate3d(${x}px, ${y}px, 0)`

    return new Promise<void>((resolve) => {
      let settled = false
      const done = (): void => {
        if (settled) return
        settled = true
        el.removeEventListener('transitionend', done)
        clearTimeout(timer)
        resolve()
      }
      el.addEventListener('transitionend', done, { once: true })
      // Fallback so background tabs / happy-dom (which never fires
      // transitionend) don't leak unresolved Promises into the SW's await chain.
      const timer = setTimeout(done, TRANSITION_FALLBACK_MS)
    })
  }

  /**
   * Detach + drop references. Safe to call when not mounted (idempotent).
   */
  unmount(): void {
    if (!this.container) return
    this.container.remove()
    this.container = null
    this.plainSvg = null
    this.styledSvg = null
  }

  /**
   * Swap which of the two SVGs is visible. Both are present in the DOM at all
   * times — only `display` flips, so no layout thrash.
   */
  setStyle(theme: PhantomCursorTheme): void {
    this.currentTheme = theme
    if (!this.plainSvg || !this.styledSvg) return
    this.plainSvg.style.display = theme === 'plain' ? '' : 'none'
    this.styledSvg.style.display = theme === 'styled' ? '' : 'none'
  }
}

function prefersReducedMotion(): boolean {
  // Cautious feature-detect — content scripts may run inside contexts where
  // matchMedia is restricted (very rare, but cheap to guard).
  if (typeof window.matchMedia !== 'function') return false
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

const STYLE_ID = 'mateclaw-phantom-cursor-style'

/**
 * Inject the page-level stylesheet once. Carries only the
 * @media (prefers-reduced-motion: reduce) override — everything else stays
 * inline on the element for testability. !important is required because the
 * cursor's transition is also set inline on .style.transition; without it the
 * inline rule wins and the user's motion preference is ignored.
 *
 * Idempotent: subsequent calls (re-injection / re-mount cycles) no-op.
 */
function ensureStyles(): void {
  if (document.getElementById(STYLE_ID)) return
  const style = document.createElement('style')
  style.id = STYLE_ID
  style.textContent = `
@media (prefers-reduced-motion: reduce) {
  #${CONTAINER_ID} { transition: transform ${REDUCED_TRANSITION_MS}ms linear !important; }
}
`
  document.head.appendChild(style)
}

function makeArrowSvg(theme: PhantomCursorTheme): SVGElement {
  const ns = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(ns, 'svg')
  svg.setAttribute('width', String(SVG_WIDTH))
  svg.setAttribute('height', String(SVG_HEIGHT))
  svg.setAttribute('viewBox', SVG_VIEWBOX)
  svg.setAttribute('data-theme', theme)
  // Make sure SVGs sit in the same spot — absolute relative to the container.
  ;(svg as unknown as SVGElement).style.position = 'absolute'
  ;(svg as unknown as SVGElement).style.top = '0'
  ;(svg as unknown as SVGElement).style.left = '0'

  if (theme === 'styled') {
    // Brand-colored arrow with a soft drop-shadow filter for the "agent is
    // active" look. Filter is inlined to keep the content script self-contained.
    const defs = document.createElementNS(ns, 'defs')
    const filter = document.createElementNS(ns, 'filter')
    filter.setAttribute('id', 'mateclaw-phantom-glow')
    filter.setAttribute('x', '-50%')
    filter.setAttribute('y', '-50%')
    filter.setAttribute('width', '200%')
    filter.setAttribute('height', '200%')
    const blur = document.createElementNS(ns, 'feGaussianBlur')
    blur.setAttribute('stdDeviation', '1.5')
    blur.setAttribute('result', 'glow')
    const merge = document.createElementNS(ns, 'feMerge')
    const m1 = document.createElementNS(ns, 'feMergeNode')
    m1.setAttribute('in', 'glow')
    const m2 = document.createElementNS(ns, 'feMergeNode')
    m2.setAttribute('in', 'SourceGraphic')
    merge.appendChild(m1)
    merge.appendChild(m2)
    filter.appendChild(blur)
    filter.appendChild(merge)
    defs.appendChild(filter)
    svg.appendChild(defs)
  }

  const path = document.createElementNS(ns, 'path')
  path.setAttribute('d', ARROW_PATH)
  if (theme === 'plain') {
    path.setAttribute('fill', '#FFFFFF')
    path.setAttribute('stroke', '#222222')
    path.setAttribute('stroke-width', '1.5')
    path.setAttribute('stroke-linejoin', 'round')
  } else {
    // Styled = MateClaw brand. We pick a saturated blue to stay distinct from
    // typical content (red/orange CTAs, green success indicators). Filter
    // provides the glow per research §2.1.
    path.setAttribute('fill', '#3D75FF')
    path.setAttribute('stroke', '#FFFFFF')
    path.setAttribute('stroke-width', '1')
    path.setAttribute('stroke-linejoin', 'round')
    path.setAttribute('filter', 'url(#mateclaw-phantom-glow)')
  }
  svg.appendChild(path)
  return svg
}
