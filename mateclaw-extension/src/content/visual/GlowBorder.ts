/**
 * GlowBorder — a full-viewport inner-shadow halo that pulses softly while the
 * agent is acting on the current tab. Visual contract per research §3:
 *
 *   - Outer <div id="mateclaw-glow-border"> fills the viewport, opacity faded
 *     in over 300ms.
 *   - Inner <div id="mateclaw-glow-border-inner"> carries three stacked
 *     inset box-shadows in the brand color (15px / 25px / 35px radii) for
 *     a layered halo.
 *   - @keyframes mateclaw-pulse animates inner opacity 0.6 ↔ 1.0 every 2s.
 *   - @keyframes are injected once via <style id="mateclaw-glow-anim">.
 *   - prefers-reduced-motion: reduce → animation: none (P1-8).
 *
 * State machine:
 *
 *   HIDDEN ── show() ──▶ SHOWING ── opacity reaches 1 ──▶ VISIBLE
 *      ▲                    │                                │
 *      │                  hide()                          hide()
 *      │                    ▼                                ▼
 *      └────────────────── HIDING ◀──────────────────────────┘
 *
 *   hide() during SHOWING must NOT leave a stale DOM node — we cancel the
 *   "show settled" timer and immediately move to HIDING. The fade-out timer
 *   then removes the node when it lands.
 */

const OUTER_ID = 'mateclaw-glow-border'
const INNER_ID = 'mateclaw-glow-border-inner'
const STYLE_ID = 'mateclaw-glow-anim'
const KEYFRAMES_NAME = 'mateclaw-pulse'

const SHOW_TRANSITION_MS = 300
const HIDE_TRANSITION_MS = 300
// Pulse period chosen per research §3.1 (2s ease-in-out, opacity 0.6 ↔ 1.0).
const PULSE_PERIOD_S = 2

// z-index must sit above page content but BELOW the stop button (which uses
// the absolute max). Both glow and stop are pinned at the max integer in
// the plan, but the stop button is appended later so DOM order keeps it on
// top. We still nudge the glow one tick down to be defensive — the stop
// button must always be clickable.
const Z_INDEX = 2147483647

// Brand color in RGB triples — kept in sync with PhantomCursor's styled fill
// and the StopButton. Matches the official "Claude in Chrome" agent indicator
// terracotta (#D97757 → rgb(217, 119, 87)). Consumers may override via the
// --mateclaw-brand-rgb CSS variable before calling show().
const DEFAULT_BRAND_RGB = '217, 119, 87'

type State = 'HIDDEN' | 'SHOWING' | 'VISIBLE' | 'HIDING'

export class GlowBorder {
  private outer: HTMLDivElement | null = null
  private state: State = 'HIDDEN'
  private showTimer: number | null = null
  private hideTimer: number | null = null

  show(): void {
    if (this.state === 'VISIBLE' || this.state === 'SHOWING') {
      // Idempotent — already shown / showing.
      return
    }
    // Cancel any in-flight hide so we don't tear down what we're about to mount.
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer)
      this.hideTimer = null
    }
    // If we're transitioning from HIDING back to SHOWING and the node already
    // exists, just re-bump opacity and reset the state.
    if (this.outer) {
      this.state = 'SHOWING'
      this.outer.style.opacity = '1'
      this.scheduleShowSettle()
      return
    }

    ensureKeyframes()

    const reduced = prefersReducedMotion()
    const brand = readBrandRgb()

    const outer = document.createElement('div')
    outer.id = OUTER_ID
    outer.style.position = 'fixed'
    outer.style.top = '0'
    outer.style.left = '0'
    outer.style.right = '0'
    outer.style.bottom = '0'
    outer.style.pointerEvents = 'none'
    outer.style.zIndex = String(Z_INDEX)
    outer.style.opacity = '0'
    outer.style.transition = `opacity ${SHOW_TRANSITION_MS}ms ease-in-out`

    const inner = document.createElement('div')
    inner.id = INNER_ID
    inner.style.position = 'absolute'
    inner.style.top = '0'
    inner.style.left = '0'
    inner.style.right = '0'
    inner.style.bottom = '0'
    inner.style.pointerEvents = 'none'
    // Three stacked inset shadows: near / mid / far. Each successive layer is
    // wider and dimmer, giving the soft halo per research §3.1.
    inner.style.boxShadow = [
      `inset 0 0 15px rgba(${brand}, 0.7)`,
      `inset 0 0 25px rgba(${brand}, 0.5)`,
      `inset 0 0 35px rgba(${brand}, 0.2)`,
    ].join(', ')
    if (reduced) {
      // P1-8: drop the animation entirely under prefers-reduced-motion.
      inner.style.animation = 'none'
    } else {
      inner.style.animation = `${KEYFRAMES_NAME} ${PULSE_PERIOD_S}s ease-in-out infinite`
    }
    outer.appendChild(inner)

    document.body.appendChild(outer)
    this.outer = outer
    this.state = 'SHOWING'

    // Kick off the fade-in by flipping opacity on the next frame. requestAnimationFrame
    // isn't always reliable inside headless test envs, so we set the target
    // directly — the transition still runs because the initial opacity:0 was
    // already committed to the element's style.
    // (We rely on the browser flushing the style before reading it on the next tick.)
    requestAnimationFrameSafe(() => {
      if (this.outer) this.outer.style.opacity = '1'
    })

    this.scheduleShowSettle()
  }

  hide(): void {
    if (this.state === 'HIDDEN') return
    // Cancel any pending "show settled" timer — we're going down, not up.
    if (this.showTimer !== null) {
      clearTimeout(this.showTimer)
      this.showTimer = null
    }
    if (!this.outer) {
      // Defensive: shouldn't happen in any state but HIDDEN.
      this.state = 'HIDDEN'
      return
    }
    this.state = 'HIDING'
    this.outer.style.transition = `opacity ${HIDE_TRANSITION_MS}ms ease-in-out`
    this.outer.style.opacity = '0'

    if (this.hideTimer !== null) clearTimeout(this.hideTimer)
    this.hideTimer = window.setTimeout(() => {
      this.hideTimer = null
      if (this.outer) {
        this.outer.remove()
        this.outer = null
      }
      this.state = 'HIDDEN'
    }, HIDE_TRANSITION_MS)
  }

  private scheduleShowSettle(): void {
    if (this.showTimer !== null) clearTimeout(this.showTimer)
    this.showTimer = window.setTimeout(() => {
      this.showTimer = null
      // Only promote SHOWING → VISIBLE. If hide() raced in, state will already
      // be HIDING and we leave it alone.
      if (this.state === 'SHOWING') this.state = 'VISIBLE'
    }, SHOW_TRANSITION_MS)
  }
}

function ensureKeyframes(): void {
  if (document.getElementById(STYLE_ID)) return
  const style = document.createElement('style')
  style.id = STYLE_ID
  // Keyframes match research §3.1. The reduced-motion media query suppresses
  // the animation declaratively as a belt-and-suspenders alongside the inline
  // `animation: none` we set on the inner div.
  style.textContent = `
@keyframes ${KEYFRAMES_NAME} {
  0%   { opacity: 0.6; }
  50%  { opacity: 1.0; }
  100% { opacity: 0.6; }
}
@media (prefers-reduced-motion: reduce) {
  #${INNER_ID} { animation: none !important; }
}
`
  document.head.appendChild(style)
}

function readBrandRgb(): string {
  // Honour --mateclaw-brand-rgb on <html> if the page (or sibling indicators)
  // already established a theme. Defaults to the saturated blue used by
  // PhantomCursor.
  if (typeof document.documentElement.style?.getPropertyValue !== 'function') {
    return DEFAULT_BRAND_RGB
  }
  const v = document.documentElement.style.getPropertyValue('--mateclaw-brand-rgb')
  return (v && v.trim().length > 0) ? v.trim() : DEFAULT_BRAND_RGB
}

function prefersReducedMotion(): boolean {
  if (typeof window.matchMedia !== 'function') return false
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

function requestAnimationFrameSafe(cb: () => void): void {
  if (typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(cb)
  } else {
    setTimeout(cb, 0)
  }
}
