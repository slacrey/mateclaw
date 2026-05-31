import { describe, expect, it, vi } from 'vitest'
import { extractAxTreeViaCdp, type SnapshotFilter } from './cdp-ax-extractor'
import type { DebuggerManager } from './debugger-manager'
import type { AXNode, BoxModel } from './cdp-types'

// ---------------------------------------------------------------------------
// Server-side LINE_PATTERN (verbatim from PageSnapshot.java) — every emitted
// line that carries a bbox MUST match this so the grounding parser accepts it.
// Lines without a bbox are intentionally NOT matched by the server (it skips
// them), exactly like the JS walker's roleless/zero-rect lines.
// ---------------------------------------------------------------------------
const SERVER_LINE_PATTERN =
  /^([A-Za-z][\w-]*)\s*\[ref=([\w-]+)(?:\s*,\s*frame=(\d+))?\]\s*(?::\s*(.+?))?\s*@\{(\d+),(\d+)\s+(\d+)x(\d+)\}\s*$/

/** Build an AXValue-ish wrapper. */
function role(value: string): AXNode['role'] {
  return { type: 'role', value }
}
function name(value: string): AXNode['name'] {
  return { type: 'computedString', value }
}

/** A square content quad at (x,y) sized w×h: [x,y, x+w,y, x+w,y+h, x,y+h]. */
function quad(x: number, y: number, w: number, h: number): number[] {
  return [x, y, x + w, y, x + w, y + h, x, y + h]
}

function boxModel(x: number, y: number, w: number, h: number): BoxModel {
  const content = quad(x, y, w, h)
  return { content, padding: content, border: content, margin: content, width: w, height: h }
}

interface FakeOpts {
  nodes: AXNode[]
  /** backendDOMNodeId → box model. Missing ids reject (no layout box). */
  boxes?: Record<number, BoxModel>
  /** force getFullAXTree to throw */
  axTreeThrows?: boolean
}

/**
 * Stub DebuggerManager.send: routes Accessibility.getFullAXTree to the fixture
 * node list and DOM.getBoxModel to the per-backendId box map (rejecting when
 * the id is absent, to exercise the bbox-omitted path). attach() is a no-op.
 */
function fakeManager(opts: FakeOpts) {
  const send = vi.fn(async (_tabId: number, method: string, params: unknown) => {
    if (method === 'Accessibility.getFullAXTree') {
      if (opts.axTreeThrows) throw new Error('CDP unsupported')
      return { nodes: opts.nodes }
    }
    if (method === 'DOM.getBoxModel') {
      const backendId = (params as { backendNodeId?: number }).backendNodeId
      const model = backendId != null ? opts.boxes?.[backendId] : undefined
      if (!model) throw new Error('Could not compute box model')
      return { model }
    }
    throw new Error(`unexpected CDP method ${method}`)
  })
  const manager = {
    attach: vi.fn(async () => {}),
    detach: vi.fn(async () => {}),
    send,
    isAttached: () => true,
  } as unknown as DebuggerManager
  return { manager, send }
}

async function extract(opts: FakeOpts, filter: SnapshotFilter, maxChars = 200000): Promise<string> {
  const { manager } = fakeManager(opts)
  return extractAxTreeViaCdp(manager, 42, filter, maxChars)
}

// A small, realistic top-frame tree:
//   RootWebArea
//     Heading "Welcome"
//     Button "筛选"   (interactive)
//     Link "Learn more"  (interactive)
//     Navigation (landmark)
//       Button "Home"
//     StaticText "just text"   (only under 'all')
//     none/generic "ignored-role"  (roleless; named-only under 'all')
function basicTree(): AXNode[] {
  return [
    { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2', '3', '4', '5', '7', '8'], backendDOMNodeId: 100 },
    { nodeId: '2', role: role('heading'), name: name('Welcome'), backendDOMNodeId: 102 },
    { nodeId: '3', role: role('button'), name: name('筛选'), backendDOMNodeId: 103 },
    { nodeId: '4', role: role('link'), name: name('Learn more'), backendDOMNodeId: 104 },
    { nodeId: '5', role: role('navigation'), name: name(''), childIds: ['6'], backendDOMNodeId: 105 },
    { nodeId: '6', role: role('button'), name: name('Home'), backendDOMNodeId: 106 },
    { nodeId: '7', role: role('StaticText'), name: name('just text'), backendDOMNodeId: 107 },
    { nodeId: '8', role: role('generic'), name: name('plain container'), backendDOMNodeId: 108 },
  ]
}

function basicBoxes(): Record<number, BoxModel> {
  return {
    102: boxModel(50, 100, 700, 40),
    103: boxModel(600, 18, 64, 32),
    104: boxModel(200, 400, 120, 18),
    105: boxModel(0, 0, 1280, 60),
    106: boxModel(20, 10, 50, 30),
    107: boxModel(10, 500, 80, 16),
    108: boxModel(0, 600, 200, 20),
  }
}

describe('extractAxTreeViaCdp', () => {
  it('emits the frozen format: Role[ref=ref_N, frame=0]: name @{x,y wxh}', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'interactive')
    const lines = tree.split('\n')

    // RootWebArea is not interactive/landmark/heading → not emitted under
    // 'interactive'. Heading is ALWAYS emitted; button/link are interactive.
    // Navigation (landmark) is NOT emitted under 'interactive'. Its child
    // button IS interactive but, since its parent wasn't emitted, it sits at
    // the same output depth as the others (output depth tracks EMITTED tree).
    expect(lines).toEqual([
      'Heading[ref=ref_1, frame=0]: Welcome @{50,100 700x40}',
      'Button[ref=ref_2, frame=0]: 筛选 @{600,18 64x32}',
      'Link[ref=ref_3, frame=0]: Learn more @{200,400 120x18}',
      'Button[ref=ref_4, frame=0]: Home @{20,10 50x30}',
    ])
  })

  it('every emitted line matches the server LINE_PATTERN regex', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'all')
    for (const raw of tree.split('\n')) {
      const line = raw.trim()
      if (!line) continue
      const m = SERVER_LINE_PATTERN.exec(line)
      expect(m, `line did not match server pattern: ${line}`).not.toBeNull()
      // role token = group 1, ref = group 2, frame = group 3
      expect(m![2]).toMatch(/^ref_\d+$/)
      expect(m![3]).toBe('0')
    }
  })

  it('filter=interactive keeps only buttons/links/inputs/headings, drops landmarks + text', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'interactive')
    expect(tree).not.toContain('Navigation')
    expect(tree).not.toContain('Text[') // StaticText → Text only under 'all'
    expect(tree).toContain('Button[ref=ref_2, frame=0]: 筛选')
    expect(tree).toContain('Heading[ref=ref_1, frame=0]: Welcome')
  })

  it('filter=default adds landmarks on top of interactive', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'default')
    expect(tree).toContain('Navigation[ref=')
    // Navigation emitted → its child Button indents one output level deeper.
    const lines = tree.split('\n')
    const navIdx = lines.findIndex(l => l.includes('Navigation['))
    expect(navIdx).toBeGreaterThanOrEqual(0)
    const homeLine = lines.find(l => l.includes(': Home'))!
    expect(homeLine.startsWith('  ')).toBe(true) // 2-space indent under Navigation
  })

  it('filter=all surfaces StaticText and named roleless nodes as Text', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'all')
    expect(tree).toContain('Text[')
    expect(tree).toContain(': just text')
    // The 'generic' roleless node has a name → emitted as Text under 'all'.
    expect(tree).toContain(': plain container')
    // RootWebArea is a document container with no ARIA token → folded to
    // roleless and suppressed (no JS-walker counterpart). It must NOT leak as
    // a 'Rootwebarea'/'Text: <title>' line.
    expect(tree).not.toContain('Rootwebarea')
    expect(tree).not.toContain(': Doc')
  })

  it('skips ignored nodes', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2', '3'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('button'), name: name('Visible'), backendDOMNodeId: 102 },
      { nodeId: '3', role: role('button'), name: name('Hidden'), ignored: true, backendDOMNodeId: 103 },
    ]
    const boxes = { 102: boxModel(1, 2, 3, 4), 103: boxModel(5, 6, 7, 8) }
    const tree = await extract({ nodes, boxes }, 'interactive')
    expect(tree).toContain(': Visible')
    expect(tree).not.toContain(': Hidden')
  })

  it('omits the @{...} bbox segment when getBoxModel has no box (offscreen / detached)', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2', '3'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('button'), name: name('Boxed'), backendDOMNodeId: 102 },
      { nodeId: '3', role: role('button'), name: name('NoBox'), backendDOMNodeId: 103 },
    ]
    // Only 102 has a box; 103 → getBoxModel rejects → bbox omitted.
    const tree = await extract({ nodes, boxes: { 102: boxModel(10, 20, 30, 40) } }, 'interactive')
    const lines = tree.split('\n')
    expect(lines).toContain('Button[ref=ref_1, frame=0]: Boxed @{10,20 30x40}')
    expect(lines).toContain('Button[ref=ref_2, frame=0]: NoBox')
    // The NoBox line has no bbox → must NOT match the server pattern (skipped server-side).
    expect(SERVER_LINE_PATTERN.test('Button[ref=ref_2, frame=0]: NoBox')).toBe(false)
  })

  it('treats an all-zero box at the origin as no rect (bbox omitted)', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('button'), name: name('Zero'), backendDOMNodeId: 102 },
    ]
    const tree = await extract({ nodes, boxes: { 102: boxModel(0, 0, 0, 0) } }, 'interactive')
    expect(tree).toBe('Button[ref=ref_1, frame=0]: Zero')
  })

  it('sanitizes names so a literal " @{" cannot inject a bbox marker', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('button'), name: name('weird @{x,y} label'), backendDOMNodeId: 102 },
    ]
    const tree = await extract({ nodes, boxes: { 102: boxModel(5, 5, 10, 10) } }, 'interactive')
    // The ASCII ' @{' inside the name is swapped to the fullwidth '＠{'.
    expect(tree).toContain('＠{x,y}')
    expect(tree).toBe('Button[ref=ref_1, frame=0]: weird ＠{x,y} label @{5,5 10x10}')
    // Still parseable by the server: exactly one trailing bbox group.
    const m = SERVER_LINE_PATTERN.exec(tree)
    expect(m).not.toBeNull()
    expect(m![4]).toBe('weird ＠{x,y} label')
    expect(m![5]).toBe('5')
  })

  it('collapses internal whitespace runs in names', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('button'), name: name('  multi   line\n\tname  '), backendDOMNodeId: 102 },
    ]
    const tree = await extract({ nodes, boxes: { 102: boxModel(1, 1, 2, 2) } }, 'interactive')
    expect(tree).toBe('Button[ref=ref_1, frame=0]: multi line name @{1,1 2x2}')
  })

  it('ref_N is a per-call 1-based counter in emission order', async () => {
    const tree = await extract({ nodes: basicTree(), boxes: basicBoxes() }, 'interactive')
    const refs = [...tree.matchAll(/ref=(ref_\d+)/g)].map(m => m[1])
    expect(refs).toEqual(['ref_1', 'ref_2', 'ref_3', 'ref_4'])
  })

  it('maps image role to the Image label', async () => {
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['2'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('image'), name: name('logo'), backendDOMNodeId: 102 },
    ]
    const tree = await extract({ nodes, boxes: { 102: boxModel(0, 0, 40, 40) } }, 'all')
    expect(tree).toContain('Image[ref=ref_1, frame=0]: logo @{0,0 40x40}')
  })

  it('respects maxChars with the "...TRUNCATED at N bytes" convention', async () => {
    // Many buttons → long output. Cap small enough to force a cut.
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name('Doc'), childIds: ['a', 'b', 'c', 'd'], backendDOMNodeId: 1 },
      { nodeId: 'a', role: role('button'), name: name('AAAAAAAAAA'), backendDOMNodeId: 11 },
      { nodeId: 'b', role: role('button'), name: name('BBBBBBBBBB'), backendDOMNodeId: 12 },
      { nodeId: 'c', role: role('button'), name: name('CCCCCCCCCC'), backendDOMNodeId: 13 },
      { nodeId: 'd', role: role('button'), name: name('DDDDDDDDDD'), backendDOMNodeId: 14 },
    ]
    const boxes = { 11: boxModel(1, 1, 1, 1), 12: boxModel(2, 2, 1, 1), 13: boxModel(3, 3, 1, 1), 14: boxModel(4, 4, 1, 1) }
    const tree = await extract({ nodes, boxes }, 'interactive', 80)
    expect(tree).toContain('...TRUNCATED at 80 bytes')
    // Truncation cuts on a newline boundary — no half-line before the marker.
    const beforeMarker = tree.split('\n...TRUNCATED')[0] ?? ''
    for (const line of beforeMarker.split('\n')) {
      expect(SERVER_LINE_PATTERN.test(line)).toBe(true)
    }
  })

  it('throws when getFullAXTree returns no nodes (caller falls back to JS walker)', async () => {
    await expect(extract({ nodes: [] }, 'interactive')).rejects.toThrow()
  })

  it('throws when the AX tree yields zero emittable nodes', async () => {
    // Only roleless/unnamed nodes → nothing passes the 'interactive' filter.
    const nodes: AXNode[] = [
      { nodeId: '1', role: role('RootWebArea'), name: name(''), childIds: ['2'], backendDOMNodeId: 100 },
      { nodeId: '2', role: role('generic'), name: name(''), backendDOMNodeId: 102 },
    ]
    await expect(extract({ nodes }, 'interactive')).rejects.toThrow()
  })

  it('throws when getFullAXTree itself rejects (CDP unsupported)', async () => {
    await expect(extract({ nodes: basicTree(), axTreeThrows: true }, 'interactive')).rejects.toThrow()
  })

  it('attaches once and issues exactly one getFullAXTree + one getBoxModel per kept node with a backend id', async () => {
    const { manager, send } = fakeManager({ nodes: basicTree(), boxes: basicBoxes() })
    await extractAxTreeViaCdp(manager, 42, 'interactive', 200000)
    const methods = send.mock.calls.map(c => c[1])
    expect(methods.filter(m => m === 'Accessibility.getFullAXTree')).toHaveLength(1)
    // 4 kept nodes under 'interactive' (Heading, Button, Link, Home Button),
    // all carry a backendDOMNodeId → 4 getBoxModel calls.
    expect(methods.filter(m => m === 'DOM.getBoxModel')).toHaveLength(4)
  })
})
