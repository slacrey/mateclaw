/**
 * StaticIndicator — bottom-center pill shown on every tab that belongs to the
 * MateClaw-controlled Chrome tab group. It mirrors the "active in this tab
 * group" affordance from Claude's extension, but keeps all runtime messaging
 * in visual-indicator.ts so this component stays DOM-only and unit-testable.
 */

const CONTAINER_ID = 'mateclaw-static-indicator-container'
const FOCUS_BUTTON_ID = 'mateclaw-static-focus-button'
const DISMISS_BUTTON_ID = 'mateclaw-static-dismiss-button'
const STYLE_ID = 'mateclaw-static-indicator-style'

const Z_INDEX = 2147483647
const SURFACE = '#FAF9F5'
const HOVER = '#F0EEE6'
const INK = '#141413'
const BRAND = '#D97757'

export class StaticIndicator {
  private container: HTMLDivElement | null = null
  private focusHandler: (() => void) | null = null
  private dismissHandler: (() => void) | null = null

  show(opts: { dismissed?: boolean } = {}): void {
    if (opts.dismissed) {
      this.hide()
      return
    }
    if (this.container) return

    ensureStyles()

    const container = document.createElement('div')
    container.id = CONTAINER_ID
    container.setAttribute('role', 'status')
    container.setAttribute('aria-live', 'polite')
    container.style.position = 'fixed'
    container.style.left = '50%'
    container.style.bottom = '16px'
    container.style.transform = 'translateX(-50%)'
    container.style.display = 'inline-flex'
    container.style.alignItems = 'center'
    container.style.justifyContent = 'center'
    container.style.gap = '8px'
    container.style.padding = '6px 6px 6px 14px'
    container.style.background = SURFACE
    container.style.border = '0.5px solid rgba(31, 30, 29, 0.30)'
    container.style.borderRadius = '14px'
    container.style.boxShadow = '0 40px 80px rgba(0, 0, 0, 0.15)'
    container.style.color = INK
    container.style.fontFamily =
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif'
    container.style.fontSize = '14px'
    container.style.whiteSpace = 'nowrap'
    container.style.userSelect = 'none'
    container.style.pointerEvents = 'none'
    container.style.zIndex = String(Z_INDEX)

    container.appendChild(makeBrandMark())

    const label = document.createElement('span')
    label.textContent = 'MateClaw is active in this tab group'
    label.style.display = 'inline-block'
    label.style.lineHeight = '20px'
    container.appendChild(label)

    const divider = document.createElement('div')
    divider.setAttribute('aria-hidden', 'true')
    divider.style.width = '0.5px'
    divider.style.height = '32px'
    divider.style.background = 'rgba(31, 30, 29, 0.15)'
    divider.style.margin = '0 2px'
    container.appendChild(divider)

    const focus = makeIconButton(FOCUS_BUTTON_ID, 'Focus the controlled tab')
    focus.appendChild(makeChatIcon())
    focus.addEventListener('click', () => {
      if (this.focusHandler) this.focusHandler()
    })
    container.appendChild(focus)

    const dismiss = makeIconButton(DISMISS_BUTTON_ID, 'Dismiss')
    dismiss.appendChild(makeCloseIcon())
    dismiss.addEventListener('click', () => {
      if (this.dismissHandler) this.dismissHandler()
    })
    container.appendChild(dismiss)

    document.body.appendChild(container)
    this.container = container
  }

  hide(): void {
    if (!this.container) return
    this.container.remove()
    this.container = null
  }

  onFocusMain(cb: () => void): void {
    this.focusHandler = cb
  }

  onDismiss(cb: () => void): void {
    this.dismissHandler = cb
  }
}

function ensureStyles(): void {
  if (document.getElementById(STYLE_ID)) return
  const style = document.createElement('style')
  style.id = STYLE_ID
  style.textContent = `
#${FOCUS_BUTTON_ID},
#${DISMISS_BUTTON_ID} {
  transition: background-color 150ms ease-in-out;
}
#${FOCUS_BUTTON_ID}:hover,
#${DISMISS_BUTTON_ID}:hover {
  background: ${HOVER} !important;
}
@media (prefers-reduced-motion: reduce) {
  #${FOCUS_BUTTON_ID},
  #${DISMISS_BUTTON_ID} {
    transition: none !important;
  }
}
`
  document.head.appendChild(style)
}

function makeIconButton(id: string, label: string): HTMLButtonElement {
  const button = document.createElement('button')
  button.id = id
  button.type = 'button'
  button.setAttribute('aria-label', label)
  button.title = label
  button.style.position = 'relative'
  button.style.display = 'inline-flex'
  button.style.alignItems = 'center'
  button.style.justifyContent = 'center'
  button.style.width = '32px'
  button.style.height = '32px'
  button.style.padding = '6px'
  button.style.border = 'none'
  button.style.borderRadius = '8px'
  button.style.background = 'transparent'
  button.style.color = INK
  button.style.cursor = 'pointer'
  button.style.pointerEvents = 'auto'
  return button
}

function makeBrandMark(): SVGElement {
  const ns = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(ns, 'svg')
  svg.setAttribute('width', '16')
  svg.setAttribute('height', '16')
  svg.setAttribute('viewBox', '0 0 16 16')
  svg.setAttribute('aria-hidden', 'true')
  svg.style.flexShrink = '0'
  svg.style.marginRight = '2px'

  const outer = document.createElementNS(ns, 'circle')
  outer.setAttribute('cx', '8')
  outer.setAttribute('cy', '8')
  outer.setAttribute('r', '7')
  outer.setAttribute('fill', BRAND)
  svg.appendChild(outer)

  const path = document.createElementNS(ns, 'path')
  path.setAttribute('d', 'M4.5 11.5V4.5h1.4L8 8.3l2.1-3.8h1.4v7h-1.3V7.2L8.5 10.3h-1L5.8 7.2v4.3z')
  path.setAttribute('fill', '#FAF9F5')
  svg.appendChild(path)

  return svg
}

function makeChatIcon(): SVGElement {
  const ns = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(ns, 'svg')
  svg.setAttribute('width', '20')
  svg.setAttribute('height', '20')
  svg.setAttribute('viewBox', '0 0 20 20')
  svg.setAttribute('fill', 'none')
  svg.setAttribute('aria-hidden', 'true')

  const path = document.createElementNS(ns, 'path')
  path.setAttribute(
    'd',
    'M10 3.25a6.75 6.75 0 0 1 0 13.5H3.75a.5.5 0 0 1-.35-.85l1.45-1.45A6.75 6.75 0 0 1 10 3.25Zm0 1.1a5.65 5.65 0 0 0-4 9.65.55.55 0 0 1 0 .78l-.87.87H10a5.65 5.65 0 1 0 0-11.3Z',
  )
  path.setAttribute('fill', 'currentColor')
  svg.appendChild(path)
  return svg
}

function makeCloseIcon(): SVGElement {
  const ns = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(ns, 'svg')
  svg.setAttribute('width', '20')
  svg.setAttribute('height', '20')
  svg.setAttribute('viewBox', '0 0 20 20')
  svg.setAttribute('fill', 'none')
  svg.setAttribute('aria-hidden', 'true')

  const path = document.createElementNS(ns, 'path')
  path.setAttribute(
    'd',
    'M5.15 4.35 10 9.2l4.85-4.85.8.8L10.8 10l4.85 4.85-.8.8L10 10.8l-4.85 4.85-.8-.8L9.2 10 4.35 5.15z',
  )
  path.setAttribute('fill', 'currentColor')
  svg.appendChild(path)
  return svg
}
