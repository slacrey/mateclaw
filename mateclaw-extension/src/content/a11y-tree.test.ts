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
    expect(tree).toContain('Button[ref=ref_1]')
    expect(tree).toContain('@{120,340 80x32}')
    expect(tree).toContain('Submit')
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
    expect(tree).toContain('Button[ref=ref_1]')
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
    expect(tree).toContain('Link[ref=ref_1]')
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
})
