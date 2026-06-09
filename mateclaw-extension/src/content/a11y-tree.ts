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

  // Roles that MUST end up with a usable name. When the standard ARIA name
  // computation comes back empty for one of these, we synthesize a name so the
  // element is actionable (otherwise an LLM can't tell a nameless React input
  // apart from any other box). 'option' is intentionally excluded — its text
  // already flows through the textContent fallback.
  const NAMEABLE_INTERACTIVE_ROLES = new Set<string>([
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

  // Roles that represent a SINGLE click target and "collapse" their subtree for
  // the weak cursor:pointer affordance. Once we're inside one of these (a real
  // <button>/<a>, role="button", a menu item, an onclick <div>, …), a descendant
  // that looks clickable ONLY because it inherited cursor:pointer is almost
  // always the same visual control — e.g. Douyin renders 筛选 as
  // <div role="button"><span>筛选</span></div> where the span inherits the
  // pointer cursor. Emitting BOTH produces two identical "Button: 筛选" lines,
  // which makes role+name grounding ambiguous (GROUNDING_AMBIGUOUS). So inside
  // these roles the cursor:pointer fallback is suppressed; explicit affordances
  // (onclick / tabindex / contenteditable) and real ARIA roles still emit, since
  // those are deliberate nested controls rather than label spans.
  const CLICK_COLLAPSE_ROLES = new Set<string>([
    'button',
    'link',
    'menuitem',
    'menuitemcheckbox',
    'menuitemradio',
    'tab',
    'option',
    'checkbox',
    'radio',
    'switch',
  ])

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

  // Synthesize a role for elements that have click affordance but no semantic
  // role (the modern-SPA pattern: <div onclick=...>, contenteditable rich-text
  // boxes, tabindex-focusable widgets). Content scripts run isolated from the
  // page, so React fiber props are unreadable — we detect affordance purely
  // from attributes we CAN see: contenteditable, tabindex, and an inline
  // onclick attribute. Returns 'textbox' for editable content, else 'button'.
  function affordanceRole(el: Element, ancestorClickable: boolean): string | null {
    const editable = el.getAttribute('contenteditable')
    if (editable !== null && editable !== 'false' && editable !== 'plaintext-false') {
      // '', 'true', 'plaintext-only' all mean editable.
      return 'textbox'
    }
    const tabindex = el.getAttribute('tabindex')
    if (tabindex !== null) {
      const n = Number.parseInt(tabindex, 10)
      if (Number.isFinite(n) && n >= 0) return 'button'
    }
    if (el.hasAttribute('onclick')) return 'button'
    // cursor:pointer is the strongest generic "this is clickable" signal for
    // React-onClick <div>/<span> menu options that carry no role / onclick attr
    // / tabindex — e.g. Douyin's 综合排序 / 最多点赞 sort items, which were
    // GROUNDING_MISS before this. Two guards keep it from over-emitting:
    //  1) NOT already inside a click target (ancestorClickable) — otherwise a
    //     label span that merely INHERITED the pointer cursor from its
    //     clickable parent would emit a duplicate "Button" line and make
    //     role+name grounding ambiguous (the 筛选 double-emit bug).
    //  2) a cheap shape check: only a leaf-ish element with its OWN short
    //     visible text (1..40 chars) qualifies — the shape of an option label,
    //     not a pointer-styled CONTAINER (video card, nav row).
    // getComputedStyle is only paid for those few surviving candidates.
    if (!ancestorClickable) {
      const own = directOwnText(el)
      if (own.length > 0 && own.length <= 40 && hasPointerCursor(el)) {
        return 'button'
      }
    }
    return null
  }

  /** Text in the element's OWN text nodes (not descendants), whitespace-collapsed. */
  function directOwnText(el: Element): string {
    let t = ''
    const kids = el.childNodes
    for (let i = 0; i < kids.length; i += 1) {
      const node = kids.item(i)
      if (node && node.nodeType === 3 /* TEXT_NODE */) t += node.textContent ?? ''
    }
    return trim(t)
  }

  /** True if computed cursor is 'pointer'. Best-effort; guarded for contexts
   *  where getComputedStyle is unavailable/throws (detached nodes, sandboxes). */
  function hasPointerCursor(el: Element): boolean {
    try {
      const win = el.ownerDocument?.defaultView
      if (!win || typeof win.getComputedStyle !== 'function') return false
      return win.getComputedStyle(el).cursor === 'pointer'
    } catch {
      return false
    }
  }

  // Capitalise role for output: 'textbox' → 'Textbox'. Special case 'img' → 'Image'.
  function roleLabel(role: string): string {
    if (role === 'img') return 'Image'
    return role.charAt(0).toUpperCase() + role.slice(1)
  }

  function trim(s: string | null | undefined): string {
    return (s ?? '').replace(/\s+/g, ' ').trim()
  }

  // Defensive guard for the frozen line grammar. The server parses the name as
  // everything between ': ' and ' @{'; a synthesized name that itself contained
  // a literal ' @{' (or a newline) would desync that parse. trim() already
  // collapses whitespace runs to single spaces, so this only has to neutralise
  // the bbox-marker bigram. We replace the '@' with a fullwidth '＠' so the
  // visible text is preserved while the ASCII ' @{' marker can't appear.
  function sanitizeName(s: string): string {
    return s.replace(/ @\{/g, ' ＠{')
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

  // True when the element's value/contents are sensitive and must never be
  // surfaced (passwords, credit-card / OTP fields). We key off the input type
  // plus name/id/autocomplete keyword hints.
  const SENSITIVE_KEYWORDS = /pass(word|wd)?|secret|otp|cvv|cvc|card[-_ ]?number|creditcard|ssn/i
  function isSensitiveField(el: Element): boolean {
    const tag = el.tagName.toUpperCase()
    if (tag === 'INPUT') {
      const t = ((el as HTMLInputElement).type || 'text').toLowerCase()
      if (t === 'password') return true
    }
    const autocomplete = (el.getAttribute('autocomplete') ?? '').toLowerCase()
    if (autocomplete.includes('password') || autocomplete.includes('cc-') || autocomplete === 'one-time-code') {
      return true
    }
    const hint = `${el.getAttribute('name') ?? ''} ${el.getAttribute('id') ?? ''}`
    return SENSITIVE_KEYWORDS.test(hint)
  }

  // Look for a nearby visible text label that the user would read as the
  // control's name: a previous element/text sibling, then a short parent that
  // wraps only this control. Capped at 40 chars so we never haul in a paragraph.
  function nearbyLabel(el: Element): string {
    const MAX = 40
    // Previous sibling text (e.g. <span>搜索</span><input>).
    let sib: ChildNode | null = el.previousSibling
    while (sib) {
      if (sib.nodeType === 3 /* Text */) {
        const t = trim(sib.textContent)
        if (t) return t.length <= MAX ? t : ''
      } else if (sib.nodeType === 1 /* Element */) {
        const t = trim((sib as Element).textContent)
        if (t) return t.length <= MAX ? t : ''
      }
      sib = sib.previousSibling
    }
    // Short wrapping parent whose entire text is the label (label-like container).
    const parent = el.parentElement
    if (parent) {
      const t = trim(parent.textContent)
      if (t && t.length <= MAX) return t
    }
    return ''
  }

  // Last-resort: humanize an actionable hint from type / class / id / data-*.
  // Recognises the common "search" affordance (incl. the CJK 搜索) so a nameless
  // SPA search box still reads as a searchbox rather than an empty line.
  function humanizedHint(el: Element): string {
    const tag = el.tagName.toUpperCase()
    if (tag === 'INPUT') {
      const t = ((el as HTMLInputElement).type || 'text').toLowerCase()
      if (t === 'search') return 'search'
      if (t === 'email') return 'email'
      if (t === 'tel') return 'phone'
      if (t === 'url') return 'url'
      if (t === 'number') return 'number'
    }
    const haystack = [
      el.getAttribute('class') ?? '',
      el.getAttribute('id') ?? '',
      el.getAttribute('name') ?? '',
      el.getAttribute('data-testid') ?? '',
      el.getAttribute('data-e2e') ?? '',
      el.getAttribute('role') ?? '',
    ]
      .join(' ')
      .toLowerCase()
    if (/(^|[-_ ])search([-_ ]|$)|搜索|searchbox|search-?input/.test(haystack)) return 'search'
    if (/(^|[-_ ])(submit|send|提交|发送)([-_ ]|$)/.test(haystack)) return 'submit'
    if (/(^|[-_ ])(close|关闭|dismiss)([-_ ]|$)/.test(haystack)) return 'close'
    if (/(^|[-_ ])(menu|菜单)([-_ ]|$)/.test(haystack)) return 'menu'
    return ''
  }

  // Synthesize a usable name for an interactive element that produced no
  // standard ARIA name. Priority: placeholder → aria-placeholder → title →
  // name attr → value (short, non-sensitive) → nearby visible label →
  // humanized type/class/id/data-* hint.
  function synthesizeName(el: Element): string {
    const placeholder = trim(el.getAttribute('placeholder'))
    if (placeholder) return placeholder
    const ariaPlaceholder = trim(el.getAttribute('aria-placeholder'))
    if (ariaPlaceholder) return ariaPlaceholder
    const title = trim(el.getAttribute('title'))
    if (title) return title
    const nameAttr = trim(el.getAttribute('name'))
    if (nameAttr) return nameAttr
    // value — only when short and not a sensitive field.
    if (!isSensitiveField(el)) {
      const rawValue =
        (el as HTMLInputElement).value ?? el.getAttribute('value') ?? ''
      const value = trim(rawValue)
      if (value && value.length < 50) return value
    }
    const near = nearbyLabel(el)
    if (near) return near
    return humanizedHint(el)
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
      const txt = trim(el.textContent)
      if (txt) return txt
    }
    // 8) Interactive but still nameless (e.g. a nameless React <input>, a
    // <div role="textbox" contenteditable>, an icon-only button). Synthesize a
    // usable name so the control is actionable instead of emitting empty.
    if (role && NAMEABLE_INTERACTIVE_ROLES.has(role)) {
      const synthesized = synthesizeName(el)
      if (synthesized) return synthesized
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
    // Names are sanitised so they can never inject the ' @{' bbox marker or a
    // newline into the line (which would desync the server's LINE_PATTERN).
    if (n.name) suffix = `: ${sanitizeName(n.name)}`
    // Per-role extras. Placeholders/values now flow through the accessible name
    // (see synthesizeName) and land in the <name> slot above — no special-case
    // line shape here. Links still append their href.
    if (n.role === 'link') {
      const href = n.el.getAttribute('href')
      if (href) suffix += ` — href=${href}`
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
    // Shadow roots already descended into — guards against re-entry loops.
    const visited = new WeakSet<ShadowRoot>()

    // Track logical (output) depth separately from DOM depth so indentation
    // reflects the EMITTED tree, not the underlying DOM nesting. ancestorClickable
    // is true once any ancestor was emitted as a single click target (see
    // CLICK_COLLAPSE_ROLES); it suppresses the weak cursor:pointer affordance on
    // descendant label spans so they don't double-emit the parent's control.
    function recurse(el: Element, outDepth: number, domDepth: number, ancestorClickable: boolean): void {
      if (domDepth > maxDepth) return
      // Skip aria-hidden subtrees.
      if (el.getAttribute('aria-hidden') === 'true') return

      // Real ARIA role first; if none, fall back to a click-affordance role so
      // <div onclick>, contenteditable boxes, and tabindex widgets become
      // actionable. The synthesized role flows into accessibleName + shouldEmit
      // exactly like a native role, so it respects the filter modes.
      const role = ariaRole(el) ?? affordanceRole(el, ancestorClickable)
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
      // Once we emit a single-click-target role, its subtree is "inside a
      // clickable" — descendant pointer-cursor label spans must not double-emit.
      const childClickable =
        ancestorClickable || (emit && role !== null && CLICK_COLLAPSE_ROLES.has(role))
      // Descend — but stop at maxDepth measured in DOM levels.
      const kids = el.children
      for (let i = 0; i < kids.length; i += 1) {
        const child = kids.item(i)
        if (child) recurse(child, childOutDepth, domDepth + 1, childClickable)
      }
      // Open shadow root: web components hang their real content off a shadow
      // tree that el.children never exposes. Closed roots return null (we can't
      // reach those and that's fine). Shadow content shares the host's
      // coordinate space, so bbox translation is unchanged. The visited guard
      // prevents pathological re-entry if a host appears in its own subtree.
      const shadow = (el as Element & { shadowRoot?: ShadowRoot | null }).shadowRoot
      if (shadow && !visited.has(shadow)) {
        visited.add(shadow)
        const shadowKids = shadow.children
        for (let i = 0; i < shadowKids.length; i += 1) {
          const child = shadowKids.item(i)
          if (child) recurse(child, childOutDepth, domDepth + 1, childClickable)
        }
      }
    }

    recurse(root, 0, 0, false)
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
    // Root at <html>, not <body>: popovers / dropdowns / filter panels are
    // frequently portaled to a container that is a SIBLING of <body> under
    // <html> (or onto <html> itself). Walking from <body> misses those, so an
    // opened filter panel would be invisible to observe even though it rendered.
    // <head> is included too but emits nothing (no roles/names/bbox), so this is
    // pure extra coverage. Non-emitting containers don't add depth, so emitted
    // indentation is unchanged vs rooting at <body>.
    const root = document.documentElement ?? document.body
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
