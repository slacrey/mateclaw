/**
 * Content script: injects window.__mateclaw_a11y_tree(filter, depth, maxChars, refId)
 *
 * Called via chrome.scripting.executeScript from the SW when an a11y.snapshot.request
 * envelope arrives. Walks the DOM (with WAI-ARIA implicit-role fallbacks since MV3
 * has no native AccessibilityTree API) and emits a plain-text serialization the
 * Control Plane can pass to an LLM cheaply.
 *
 * Output format (one element per line, two-space indent per depth level):
 *
 *   Button[ref=ref_1, frame=0]: Submit @{120,340 80x32}
 *   Link[ref=ref_2, frame=0]: Learn more — href=/docs @{200,400 120x18}
 *   Heading[ref=ref_3, frame=0]: Welcome @{50,100 700x40}
 *     Group[ref=ref_4, frame=0]
 *       Button[ref=ref_5, frame=0]: x @{280,184 30x24}
 *
 * The bbox segment is omitted when getBoundingClientRect() returns a zero rect
 * (offscreen, display:none, not yet laid out). ref_N restarts at 1 each call —
 * refs are transient handles bound to a single snapshot.
 *
 * filter:
 *   'interactive' — buttons, links, form controls, headings (semantic anchors)
 *   'all'         — every node with a non-trivial accessible name
 *   'default'     — 'interactive' plus ARIA landmarks (navigation, main, banner, ...)
 *
 * depth: max tree depth (default 15).
 * maxChars: hard cap on output length (default 200000); appends "...TRUNCATED at N bytes"
 * refId: if provided, emit ONLY the subtree rooted at the matching ref-tagged element.
 *
 * Runs in all frames. Child-frame bboxes are translated by the embedding
 * iframe chain so emitted coordinates are in the top-page coordinate space.
 */

declare global {
  interface Window {
    __mateclaw_a11y_tree?: (
      filter: 'interactive' | 'all' | 'default',
      depth?: number,
      maxChars?: number,
      refId?: string,
    ) => string
    __mateclaw_a11y_frame_id?: number
    __mateclaw_a11y_generated_frame_id?: number
  }
}

;(function install(): void {
  if (typeof window === 'undefined') return
  if (window.__mateclaw_a11y_tree) return // idempotent — content scripts can re-inject

  // ── WAI-ARIA implicit-role table (subset we care about).
  // Source: https://www.w3.org/TR/html-aria/
  // We map by tagName (uppercase) → role, with a few tag+type carve-outs handled inline.
  const IMPLICIT_ROLES: Record<string, string> = {
    A: 'link', // only when href present — checked inline
    AREA: 'link', // only when href present — checked inline
    ARTICLE: 'article',
    ASIDE: 'complementary',
    BUTTON: 'button',
    DATALIST: 'listbox',
    DD: 'definition',
    DETAILS: 'group',
    DIALOG: 'dialog',
    DT: 'term',
    FIELDSET: 'group',
    FIGURE: 'figure',
    FOOTER: 'contentinfo',
    FORM: 'form',
    H1: 'heading',
    H2: 'heading',
    H3: 'heading',
    H4: 'heading',
    H5: 'heading',
    H6: 'heading',
    HEADER: 'banner',
    HR: 'separator',
    IMG: 'img',
    LI: 'listitem',
    MAIN: 'main',
    MENU: 'list',
    NAV: 'navigation',
    OL: 'list',
    OPTGROUP: 'group',
    OPTION: 'option',
    OUTPUT: 'status',
    PROGRESS: 'progressbar',
    SECTION: 'region',
    SELECT: 'combobox',
    SUMMARY: 'button',
    TABLE: 'table',
    TBODY: 'rowgroup',
    TD: 'cell',
    TEXTAREA: 'textbox',
    TFOOT: 'rowgroup',
    TH: 'columnheader',
    THEAD: 'rowgroup',
    TR: 'row',
    UL: 'list',
  }

  // <input type="..."> → role mapping. types not listed default to 'textbox'.
  const INPUT_TYPE_ROLES: Record<string, string> = {
    button: 'button',
    checkbox: 'checkbox',
    color: 'colorwell',
    date: 'textbox',
    datetime: 'textbox',
    'datetime-local': 'textbox',
    email: 'textbox',
    file: 'button',
    hidden: '', // not exposed
    image: 'button',
    month: 'textbox',
    number: 'spinbutton',
    password: 'textbox',
    radio: 'radio',
    range: 'slider',
    reset: 'button',
    search: 'searchbox',
    submit: 'button',
    tel: 'textbox',
    text: 'textbox',
    time: 'textbox',
    url: 'textbox',
    week: 'textbox',
  }

  // Interactive roles for the 'interactive' filter. Headings are added below.
  const INTERACTIVE_ROLES = new Set<string>([
    'button',
    'link',
    'textbox',
    'searchbox',
    'combobox',
    'checkbox',
    'radio',
    'tab',
    'menuitem',
    'menuitemcheckbox',
    'menuitemradio',
    'switch',
    'slider',
    'spinbutton',
    'option',
  ])

  // Landmark roles for the 'default' filter (in addition to interactive).
  const LANDMARK_ROLES = new Set<string>([
    'banner',
    'complementary',
    'contentinfo',
    'form',
    'main',
    'navigation',
    'region',
    'search',
  ])

  // Roles we always emit because they anchor reading position.
  const ALWAYS_EMIT_ROLES = new Set<string>(['heading'])

  function ariaRole(el: Element): string | null {
    // Explicit role= wins.
    const explicit = el.getAttribute('role')
    if (explicit && explicit.trim().length > 0) {
      // Pick the first token — ARIA allows space-separated fallbacks.
      const first = explicit.trim().split(/\s+/)[0]
      if (first) return first
    }
    const tag = el.tagName.toUpperCase()
    // <a>/<area>: link role only when href is present (per WAI-ARIA implicit-role table).
    if (tag === 'A' || tag === 'AREA') {
      return el.hasAttribute('href') ? 'link' : null
    }
    if (tag === 'INPUT') {
      const t = ((el as HTMLInputElement).type || 'text').toLowerCase()
      const role = INPUT_TYPE_ROLES[t]
      return role === '' ? null : (role ?? 'textbox')
    }
    return IMPLICIT_ROLES[tag] ?? null
  }

  // Capitalise role for output: 'textbox' → 'Textbox'. Special case 'img' → 'Image'.
  function roleLabel(role: string): string {
    if (role === 'img') return 'Image'
    return role.charAt(0).toUpperCase() + role.slice(1)
  }

  function trim(s: string | null | undefined): string {
    return (s ?? '').replace(/\s+/g, ' ').trim()
  }

  function resolveLabelledBy(el: Element): string {
    const ids = (el.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter(Boolean)
    if (ids.length === 0) return ''
    const parts: string[] = []
    for (const id of ids) {
      const ref = el.ownerDocument.getElementById(id)
      if (ref) parts.push(trim(ref.textContent))
    }
    return parts.filter(Boolean).join(' ')
  }

  function labelForInput(el: Element): string {
    const id = el.getAttribute('id')
    if (id) {
      // Selector escape: ids may contain CSS-special chars; use attribute selector instead.
      const lbl = el.ownerDocument.querySelector(
        `label[for="${id.replace(/"/g, '\\"')}"]`,
      )
      if (lbl) return trim(lbl.textContent)
    }
    // Implicit label: <label><input/></label>
    let p: Element | null = el.parentElement
    while (p) {
      if (p.tagName === 'LABEL') return trim(p.textContent)
      p = p.parentElement
    }
    return ''
  }

  function accessibleName(el: Element, role: string | null): string {
    // 1) aria-label
    const al = trim(el.getAttribute('aria-label'))
    if (al) return al
    // 2) aria-labelledby
    const labelledBy = resolveLabelledBy(el)
    if (labelledBy) return labelledBy
    // 3) <label for=...> for form controls + <input>/<textarea>/<select>
    const tag = el.tagName.toUpperCase()
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
      const lbl = labelForInput(el)
      if (lbl) return lbl
      // 4) value (for buttons/submit), then placeholder
      if (tag === 'INPUT') {
        const inp = el as HTMLInputElement
        const t = (inp.type || 'text').toLowerCase()
        if ((t === 'button' || t === 'submit' || t === 'reset') && inp.value) {
          return trim(inp.value)
        }
        if (inp.placeholder) return trim(inp.placeholder)
      } else if (tag === 'TEXTAREA') {
        const ta = el as HTMLTextAreaElement
        if (ta.placeholder) return trim(ta.placeholder)
      }
    }
    // 5) alt for images / image-buttons
    if (tag === 'IMG' || (tag === 'INPUT' && (el as HTMLInputElement).type === 'image')) {
      const alt = trim(el.getAttribute('alt'))
      if (alt) return alt
    }
    // 6) title attribute as last fallback before textContent
    const title = trim(el.getAttribute('title'))
    if (title) return title
    // 7) textContent — for buttons / links / headings only (avoid grabbing
    // entire page text for generic containers).
    const textRoles = new Set([
      'button',
      'link',
      'heading',
      'tab',
      'menuitem',
      'option',
      'cell',
      'columnheader',
      'rowheader',
      'definition',
      'term',
    ])
    if (role && textRoles.has(role)) {
      return trim(el.textContent)
    }
    return ''
  }

  function getFrameOffsetToPage(): { x: number; y: number } {
    let x = 0
    let y = 0
    let win: Window | null = window

    while (win && !isTopWindow(win)) {
      let frameEl: Element | null = null
      try {
        frameEl = win.frameElement
      } catch {
        break
      }
      if (!frameEl || typeof frameEl.getBoundingClientRect !== 'function') break

      const rect = frameEl.getBoundingClientRect()
      x += rect.left
      y += rect.top

      try {
        win = win.parent
      } catch {
        break
      }
    }

    return { x, y }
  }

  function isTopWindow(win: Window): boolean {
    try {
      return win === win.top
    } catch {
      return false
    }
  }

  function currentFrameId(): number {
    const injected = window.__mateclaw_a11y_frame_id
    if (isNonNegativeInteger(injected)) return injected
    if (isTopWindow(window)) return 0

    const generated = window.__mateclaw_a11y_generated_frame_id
    if (isPositiveInteger(generated)) return generated

    const fallback = generateFallbackFrameId()
    window.__mateclaw_a11y_generated_frame_id = fallback
    return fallback
  }

  function isNonNegativeInteger(value: unknown): value is number {
    return typeof value === 'number' && Number.isInteger(value) && value >= 0
  }

  function isPositiveInteger(value: unknown): value is number {
    return typeof value === 'number' && Number.isInteger(value) && value > 0
  }

  function generateFallbackFrameId(): number {
    const runtimeId =
      typeof chrome !== 'undefined' && chrome.runtime?.id ? chrome.runtime.id : ''
    let hash = 0
    for (let i = 0; i < runtimeId.length; i += 1) {
      hash = (hash * 31 + runtimeId.charCodeAt(i)) % 999_999
    }
    return 1 + ((hash + Math.floor(Math.random() * 999_999)) % 999_999)
  }

  function bbox(
    el: Element,
    frameOffset: { x: number; y: number },
  ): { x: number; y: number; w: number; h: number } | null {
    if (typeof (el as { getBoundingClientRect?: () => DOMRect }).getBoundingClientRect !== 'function') {
      return null
    }
    const r = (el as Element & { getBoundingClientRect(): DOMRect }).getBoundingClientRect()
    const localX = Math.round(r.left)
    const localY = Math.round(r.top)
    const w = Math.round(r.width)
    const h = Math.round(r.height)
    if (w === 0 && h === 0 && localX === 0 && localY === 0) return null
    const x = Math.round(r.left + frameOffset.x)
    const y = Math.round(r.top + frameOffset.y)
    return { x, y, w, h }
  }

  function shouldEmit(
    role: string | null,
    name: string,
    filter: 'interactive' | 'all' | 'default',
  ): boolean {
    if (role && ALWAYS_EMIT_ROLES.has(role)) return true
    if (filter === 'all') {
      // Emit if it has a role OR a non-empty accessible name.
      return Boolean(role) || name.length > 0
    }
    if (!role) return false
    if (filter === 'interactive') return INTERACTIVE_ROLES.has(role)
    // 'default' = interactive + landmarks
    return INTERACTIVE_ROLES.has(role) || LANDMARK_ROLES.has(role)
  }

  function isContainerForTraversal(role: string | null): boolean {
    // We always descend; this hook exists so future filters can skip whole subtrees
    // (e.g. inside aria-hidden=true). For now, only aria-hidden cuts traversal.
    return role !== null || true
  }

  interface Node {
    el: Element
    role: string
    name: string
    depth: number
    ref: string
    frameId: number
    rect: { x: number; y: number; w: number; h: number } | null
  }

  function formatNode(n: Node): string {
    const indent = '  '.repeat(n.depth)
    const label = roleLabel(n.role)
    const bb = n.rect ? ` @{${n.rect.x},${n.rect.y} ${n.rect.w}x${n.rect.h}}` : ''
    let suffix = ''
    if (n.name) suffix = `: ${n.name}`
    // Per-role extras.
    if (n.role === 'link') {
      const href = n.el.getAttribute('href')
      if (href) suffix += ` — href=${href}`
    } else if (n.role === 'textbox' || n.role === 'searchbox') {
      // If no accessible name was found but there's a placeholder, surface it.
      const tag = n.el.tagName.toUpperCase()
      if (!n.name && (tag === 'INPUT' || tag === 'TEXTAREA')) {
        const ph = (n.el as HTMLInputElement | HTMLTextAreaElement).placeholder
        if (ph) suffix = `: placeholder="${ph}"`
      }
    }
    return `${indent}${label}[ref=${n.ref}, frame=${n.frameId}]${suffix}${bb}`
  }

  function walk(
    root: Element,
    filter: 'interactive' | 'all' | 'default',
    maxDepth: number,
    frameOffset: { x: number; y: number },
    frameId: number,
  ): Node[] {
    const out: Node[] = []
    let refCounter = 0

    // Track logical (output) depth separately from DOM depth so indentation
    // reflects the EMITTED tree, not the underlying DOM nesting.
    function recurse(el: Element, outDepth: number, domDepth: number): void {
      if (domDepth > maxDepth) return
      // Skip aria-hidden subtrees.
      if (el.getAttribute('aria-hidden') === 'true') return

      const role = ariaRole(el)
      const name = accessibleName(el, role)
      const emit = shouldEmit(role, name, filter)
      let childOutDepth = outDepth
      if (emit) {
        refCounter += 1
        // Roleless emit only happens under filter='all' for elements with an
        // accessible name — surface them under the generic 'Text' label.
        const emittedRole = role ?? 'text'
        out.push({
          el,
          role: emittedRole,
          name,
          depth: outDepth,
          ref: `ref_${refCounter}`,
          frameId,
          rect: bbox(el, frameOffset),
        })
        childOutDepth = outDepth + 1
      }
      // Descend — but stop at maxDepth measured in DOM levels.
      const kids = el.children
      for (let i = 0; i < kids.length; i += 1) {
        const child = kids.item(i)
        if (child) recurse(child, childOutDepth, domDepth + 1)
      }
    }

    recurse(root, 0, 0)
    return out
  }

  function elementContains(parent: Element, child: Element): boolean {
    if (parent === child) return true
    // Standard DOM Node.contains
    if (typeof parent.contains === 'function') return parent.contains(child)
    let p: Element | null = child
    while (p) {
      if (p === parent) return true
      p = p.parentElement
    }
    return false
  }

  function truncate(s: string, maxChars: number): string {
    if (s.length <= maxChars) return s
    const head = s.slice(0, maxChars)
    // Cut at last newline so we don't slice a node line in half.
    const lastNl = head.lastIndexOf('\n')
    const safeHead = lastNl >= 0 ? head.slice(0, lastNl) : head
    return `${safeHead}\n...TRUNCATED at ${maxChars} bytes`
  }

  window.__mateclaw_a11y_tree = function tree(
    filter: 'interactive' | 'all' | 'default',
    depth: number = 15,
    maxChars: number = 200000,
    refId?: string,
  ): string {
    const root = document.body ?? document.documentElement
    if (!root) return ''

    const frameOffset = getFrameOffsetToPage()
    const frameId = currentFrameId()
    const all = walk(root, filter, depth, frameOffset, frameId)

    let toEmit: Node[]
    if (refId) {
      const anchor = all.find((n) => n.ref === refId)
      if (!anchor) return ''
      // Re-baseline indentation so the anchor sits at depth 0.
      const baseDepth = anchor.depth
      toEmit = all
        .filter((n) => elementContains(anchor.el, n.el))
        .map((n) => ({ ...n, depth: n.depth - baseDepth }))
    } else {
      toEmit = all
    }

    const lines: string[] = []
    for (const n of toEmit) {
      lines.push(formatNode(n))
    }
    const joined = lines.join('\n')
    return truncate(joined, maxChars)
  }
})()

export {}
