// @vitest-environment happy-dom
//
// happy-dom is the project default (see vite.config.ts) and provides the DOM
// APIs we need: querySelector, getAttribute, getBoundingClientRect. We do NOT
// add a jsdom dependency — the brief allowed using either.

import { describe, expect, it, beforeEach } from 'vitest'

// Helper: re-import the IIFE-bearing module after wiping window state.
// Vitest module-caches by default; vi.resetModules() forces re-execution.
import { vi } from 'vitest'

async function freshImport(): Promise<void> {
  delete (window as unknown as { __mateclaw_a11y_tree?: unknown }).__mateclaw_a11y_tree
  vi.resetModules()
  await import('./a11y-tree')
}

// Make happy-dom's bbox configurable so we can exercise the @{x,y wxh} branch
// without a real layout engine. By default happy-dom returns 0x0 rects for
// every element.
function stubBBox(el: Element, x: number, y: number, w: number, h: number): void {
  ;(el as unknown as { getBoundingClientRect: () => DOMRect }).getBoundingClientRect =
    () => ({ x, y, width: w, height: h, top: y, left: x, right: x + w, bottom: y + h, toJSON() { return this } } as DOMRect)
}

describe('a11y-tree content script', () => {
  beforeEach(async () => {
    document.body.innerHTML = ''
    delete (window as unknown as { __mateclaw_a11y_frame_id?: unknown }).__mateclaw_a11y_frame_id
    delete (window as unknown as { __mateclaw_a11y_generated_frame_id?: unknown }).__mateclaw_a11y_generated_frame_id
    await freshImport()
  })

  it('exposes __mateclaw_a11y_tree on window after import', () => {
    expect(typeof window.__mateclaw_a11y_tree).toBe('function')
  })

  it('serializes a button with name and bbox', () => {
    document.body.innerHTML = '<button id="b">Submit</button>'
    const btn = document.getElementById('b')!
    stubBBox(btn, 120, 340, 80, 32)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]')
    expect(tree).toContain('@{120,340 80x32}')
    expect(tree).toContain('Submit')
  })

  it('translates child-frame bboxes into page-absolute coordinates', () => {
    document.body.innerHTML = '<button id="b">Login</button>'
    const btn = document.getElementById('b')!
    stubBBox(btn, 10, 10, 60, 24)

    const originalTop = Object.getOwnPropertyDescriptor(window, 'top')
    const originalParent = Object.getOwnPropertyDescriptor(window, 'parent')
    const originalFrameElement = Object.getOwnPropertyDescriptor(window, 'frameElement')
    const topWindow = {}
    const iframe = {
      getBoundingClientRect: () => ({
        x: 200,
        y: 300,
        left: 200,
        top: 300,
        right: 500,
        bottom: 500,
        width: 300,
        height: 200,
        toJSON() { return this },
      }) as DOMRect,
    }

    Object.defineProperty(window, 'top', { configurable: true, value: topWindow })
    Object.defineProperty(window, 'parent', { configurable: true, value: topWindow })
    Object.defineProperty(window, 'frameElement', { configurable: true, value: iframe })
    window.__mateclaw_a11y_frame_id = 1

    try {
      const tree = window.__mateclaw_a11y_tree!('interactive')
      expect(tree).toContain('Button[ref=ref_1, frame=1]: Login @{210,310 60x24}')
    } finally {
      restoreWindowProperty('top', originalTop)
      restoreWindowProperty('parent', originalParent)
      restoreWindowProperty('frameElement', originalFrameElement)
    }
  })

  it('serializes nested interactive elements with indentation', () => {
    document.body.innerHTML = `
      <div role="group" id="g">
        <button id="b1">Outer</button>
        <div>
          <button id="b2">Inner</button>
        </div>
      </div>`
    const tree = window.__mateclaw_a11y_tree!('all')
    // Outer button must precede Inner button.
    const iOuter = tree.indexOf('Outer')
    const iInner = tree.indexOf('Inner')
    expect(iOuter).toBeGreaterThan(-1)
    expect(iInner).toBeGreaterThan(iOuter)
    // Both buttons should be indented at least once because they live under a Group.
    const innerLine = tree.split('\n').find((l) => l.includes('Inner'))!
    expect(innerLine.startsWith('  ')).toBe(true)
  })

  it('respects depth limit', () => {
    // Build a 6-level nested chain of buttons.
    let html = ''
    for (let i = 0; i < 6; i += 1) html += `<div role="group"><button>L${i}</button>`
    for (let i = 0; i < 6; i += 1) html += '</div>'
    document.body.innerHTML = html
    const shallow = window.__mateclaw_a11y_tree!('all', 2)
    // depth=2 must not surface labels from levels >= 2 (we count groups too).
    // L0 (depth 1) lives inside the first group (depth 0) — visible.
    // L5 must definitely be cut.
    expect(shallow).not.toContain('L5')
    expect(shallow).not.toContain('L4')
    // And the full tree must contain them.
    const full = window.__mateclaw_a11y_tree!('all', 20)
    expect(full).toContain('L5')
  })

  it('respects maxChars and emits TRUNCATED marker', () => {
    let html = ''
    for (let i = 0; i < 200; i += 1) html += `<button>Btn${i}</button>`
    document.body.innerHTML = html
    const tree = window.__mateclaw_a11y_tree!('interactive', 15, 200)
    expect(tree.length).toBeLessThanOrEqual(200 + 64) // truncation marker line allowance
    expect(tree).toContain('...TRUNCATED')
  })

  it('filter=interactive skips paragraphs and divs without role', () => {
    document.body.innerHTML = `
      <p>Just text</p>
      <div>Also just a wrapper</div>
      <button>Real action</button>`
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]')
    expect(tree).toContain('Real action')
    expect(tree).not.toContain('Just text')
    expect(tree).not.toContain('Also just a wrapper')
  })

  it('filter=all includes elements with accessible names but no role', () => {
    document.body.innerHTML = `
      <p aria-label="Important paragraph">visible</p>
      <button>Click</button>`
    const tree = window.__mateclaw_a11y_tree!('all')
    expect(tree).toContain('Important paragraph')
    expect(tree).toContain('Click')
  })

  it('aria-label overrides textContent for accessible name', () => {
    document.body.innerHTML = '<button aria-label="Close dialog">x</button>'
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Close dialog')
    // textContent fallback ("x") must NOT appear since aria-label took priority.
    expect(tree).not.toMatch(/:\s+x\b/)
  })

  it('refId narrows to subtree (the ref-tagged element + its descendants)', () => {
    document.body.innerHTML = `
      <button id="a">Alpha</button>
      <div role="group" id="g">
        <button id="b">Beta</button>
        <button id="c">Gamma</button>
      </div>`
    // First call to discover refs.
    const full = window.__mateclaw_a11y_tree!('all')
    expect(full).toContain('Alpha')
    expect(full).toContain('Beta')
    expect(full).toContain('Gamma')

    // Find the ref for the Group.
    const groupLine = full.split('\n').find((l) => l.includes('Group'))!
    const refMatch = /ref=(ref_\d+)/.exec(groupLine)
    expect(refMatch).not.toBeNull()
    const groupRef = refMatch![1]!

    const sub = window.__mateclaw_a11y_tree!('all', 15, 200000, groupRef)
    expect(sub).toContain('Beta')
    expect(sub).toContain('Gamma')
    expect(sub).not.toContain('Alpha')
  })

  it('idempotent install: importing twice does not double-install', async () => {
    const fn1 = window.__mateclaw_a11y_tree
    // Re-import the module without deleting the window prop. The IIFE must
    // short-circuit because the function already exists.
    vi.resetModules()
    await import('./a11y-tree')
    const fn2 = window.__mateclaw_a11y_tree
    expect(fn1).toBe(fn2)
  })

  it('bbox omitted for zero-rect (offscreen / un-laid-out) elements', () => {
    document.body.innerHTML = '<button>Hidden</button>'
    // happy-dom returns 0x0 by default — perfect for this case.
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Hidden')
    // No bbox segment when rect is zero.
    expect(tree).not.toMatch(/@\{0,0 0x0\}/)
  })

  it('input element with placeholder gets accessible name from placeholder when no label', () => {
    document.body.innerHTML = '<input type="text" placeholder="Your email" />'
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Textbox')
    expect(tree).toContain('Your email')
  })

  it('heading elements (h1-h6) emit Heading[ref] regardless of filter level (semantic anchors)', () => {
    document.body.innerHTML = `
      <h1>Welcome</h1>
      <h2>Section A</h2>
      <p>boring text</p>
      <h3>Section B</h3>`
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Heading')
    expect(tree).toContain('Welcome')
    expect(tree).toContain('Section A')
    expect(tree).toContain('Section B')
    // <p> still filtered out under 'interactive'.
    expect(tree).not.toContain('boring text')
  })

  it('Link role emits with href appended', () => {
    document.body.innerHTML = '<a href="/docs">Learn more</a>'
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Link[ref=ref_1, frame=0]')
    expect(tree).toContain('Learn more')
    expect(tree).toMatch(/href=\/docs/)
  })

  it('refs restart at ref_1 on each call', () => {
    document.body.innerHTML = '<button>Only</button>'
    const a = window.__mateclaw_a11y_tree!('interactive')
    const b = window.__mateclaw_a11y_tree!('interactive')
    expect(a).toContain('ref_1')
    expect(b).toContain('ref_1')
    expect(a).toBe(b)
  })

  it('label[for] supplies accessible name to its input', () => {
    document.body.innerHTML = `
      <label for="e">Email address</label>
      <input id="e" type="text" />`
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Email address')
  })

  // ── Nameless-interactive synthesis ──────────────────────────────────────

  it('(a) <input type="search"> with only a placeholder uses the placeholder as the NAME slot', () => {
    document.body.innerHTML = '<input type="search" placeholder="搜索视频" />'
    const inp = document.querySelector('input')!
    stubBBox(inp, 40, 12, 220, 36)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    // The placeholder lands in the <name> slot (no `placeholder="..."` shape).
    expect(tree).toContain('Searchbox[ref=ref_1, frame=0]: 搜索视频 @{40,12 220x36}')
    expect(tree).not.toContain('placeholder=')
  })

  it('(a2) Douyin-style nameless searchbox falls back to a humanized "search" hint', () => {
    // No placeholder, no aria, no label — just a search-y class. This is the
    // worst-case Douyin SPA input; it must still get an actionable name.
    document.body.innerHTML = '<input class="search-input semi-input" />'
    const inp = document.querySelector('input')!
    stubBBox(inp, 40, 12, 220, 36)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Textbox[ref=ref_1, frame=0]: search @{40,12 220x36}')
  })

  it('(b) <div role="textbox" contenteditable> with no name synthesizes from a nearby label', () => {
    document.body.innerHTML = `
      <div>
        <span>Message</span>
        <div role="textbox" contenteditable="true" id="ce"></div>
      </div>`
    const ce = document.getElementById('ce')!
    stubBBox(ce, 10, 50, 300, 80)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Textbox[ref=ref_1, frame=0]: Message @{10,50 300x80}')
  })

  it('(b2) contenteditable div with NO role still emits as Textbox (affordance role)', () => {
    document.body.innerHTML =
      '<div contenteditable="true" data-testid="composer" aria-placeholder="Write something"></div>'
    const ce = document.querySelector('[contenteditable]')!
    stubBBox(ce, 5, 5, 400, 60)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    // aria-placeholder wins over the data-* hint and lands in the name slot.
    expect(tree).toContain('Textbox[ref=ref_1, frame=0]: Write something @{5,5 400x60}')
  })

  it('(c) <div onclick> with no role emits as Button', () => {
    document.body.innerHTML = '<div onclick="doThing()" title="Play">▶</div>'
    const div = document.querySelector('div')!
    stubBBox(div, 70, 70, 44, 44)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]: Play @{70,70 44x44}')
  })

  it('(c3) cursor:pointer leaf with short text emits as Button (Douyin React-onClick sort option)', () => {
    // The 最多点赞 sort item is a role-less <div> with a React onClick (no
    // onclick attr / tabindex) — previously GROUNDING_MISS. cursor:pointer +
    // short own-text is the signal. Stub getComputedStyle so the test is
    // deterministic regardless of happy-dom's CSS engine.
    document.body.innerHTML = '<div id="opt">最多点赞</div>'
      + '<div id="card">this is a very long pointer container whose own text exceeds forty characters</div>'
    const opt = document.querySelector('#opt')!
    const card = document.querySelector('#card')!
    stubBBox(opt, 100, 200, 80, 28)
    stubBBox(card, 0, 300, 400, 400)
    const realGCS = window.getComputedStyle.bind(window)
    const spy = vi.spyOn(window, 'getComputedStyle').mockImplementation(((el: Element) => {
      if (el === opt || el === card) {
        return { cursor: 'pointer', display: 'block', visibility: 'visible', opacity: '1' } as CSSStyleDeclaration
      }
      return realGCS(el as Element)
    }) as typeof window.getComputedStyle)
    try {
      const tree = window.__mateclaw_a11y_tree!('default')
      // The short-text option is emitted as a Button…
      expect(tree).toContain('Button[ref=ref_1, frame=0]: 最多点赞 @{100,200 80x28}')
      // …but the long-text pointer CONTAINER is NOT (avoids tree bloat).
      expect(tree).not.toContain('long pointer container')
    } finally {
      spy.mockRestore()
    }
  })

  it('(c2) tabindex>=0 element with no role emits as Button under interactive filter', () => {
    document.body.innerHTML = '<div tabindex="0" aria-label="Toggle"></div>'
    const div = document.querySelector('div')!
    stubBBox(div, 1, 2, 30, 30)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]: Toggle @{1,2 30x30}')
  })

  it('(d) interactive element inside an OPEN shadow root is discovered', () => {
    document.body.innerHTML = '<div id="host"></div>'
    const host = document.getElementById('host')!
    const shadow = host.attachShadow({ mode: 'open' })
    const btn = document.createElement('button')
    btn.textContent = 'Shadow Action'
    shadow.appendChild(btn)
    stubBBox(btn, 200, 200, 100, 30)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]: Shadow Action @{200,200 100x30}')
  })

  it('(e) regression: a normal <button> still emits "Button[...]: Submit"', () => {
    document.body.innerHTML = '<button id="b">Submit</button>'
    stubBBox(document.getElementById('b')!, 120, 340, 80, 32)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Button[ref=ref_1, frame=0]: Submit @{120,340 80x32}')
  })

  it('password values are never surfaced as a synthesized name (redaction kept)', () => {
    // Password input with a value but no label/placeholder/title. The synthesis
    // path must skip the value and emit no leaked secret.
    document.body.innerHTML = '<input type="password" id="pw" />'
    const pw = document.getElementById('pw') as HTMLInputElement
    pw.value = 'hunter2-secret'
    stubBBox(pw, 0, 0, 200, 30)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).not.toContain('hunter2-secret')
    // It still emits as a Textbox (just without the secret in the name).
    expect(tree).toContain('Textbox[ref=ref_1, frame=0]')
  })

  it('text input value (short, non-sensitive) can fill the name when nothing else does', () => {
    document.body.innerHTML = '<input type="text" id="t" />'
    const t = document.getElementById('t') as HTMLInputElement
    t.value = 'prefilled query'
    stubBBox(t, 0, 0, 200, 30)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    expect(tree).toContain('Textbox[ref=ref_1, frame=0]: prefilled query @{0,0 200x30}')
  })

  it('synthesized contenteditable/searchbox nodes also emit under filter="all"', () => {
    document.body.innerHTML = `
      <input type="search" placeholder="搜索" />
      <div contenteditable="true" aria-label="Editor"></div>`
    const tree = window.__mateclaw_a11y_tree!('all')
    expect(tree).toContain('Searchbox')
    expect(tree).toContain('搜索')
    expect(tree).toContain('Editor')
  })

  it('a name containing the bbox marker is sanitized so it cannot desync the line grammar', () => {
    document.body.innerHTML = '<button aria-label="weird @{x} label">x</button>'
    const btn = document.querySelector('button')!
    stubBBox(btn, 1, 1, 10, 10)
    const tree = window.__mateclaw_a11y_tree!('interactive')
    // Exactly one real bbox marker — the synthetic ' @{' in the name is neutralised.
    expect(tree.match(/ @\{/g)!.length).toBe(1)
    expect(tree).toContain('@{1,1 10x10}')
  })
})

function restoreWindowProperty(
  key: 'top' | 'parent' | 'frameElement',
  descriptor: PropertyDescriptor | undefined,
): void {
  if (descriptor) {
    Object.defineProperty(window, key, descriptor)
  } else {
    delete (window as unknown as Record<string, unknown>)[key]
  }
}
