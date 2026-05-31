/**
 * CDP-native accessibility-tree extractor.
 *
 * A drop-in alternative to the injected-JS DOM walker in
 * {@link file://./../content/a11y-tree.ts}. Instead of running a content
 * script that re-derives WAI-ARIA roles from the DOM, this sources the tree
 * straight from Chrome's own accessibility engine over the DevTools Protocol
 * (`Accessibility.getFullAXTree`) and attaches layout bounds with
 * `DOM.getBoxModel`. The emitted text is byte-for-byte the SAME frozen format
 * the JS walker produces, so the server-side `PageSnapshot.LINE_PATTERN`
 * parser is unchanged:
 *
 *   Role[ref=ref_N, frame=N]: accessible name @{x,y wxh}
 *
 * Example: `Button[ref=ref_1, frame=0]: 筛选 @{600,18 64x32}`.
 *
 * The ` @{x,y wxh}` segment is OMITTED for zero-rect / offscreen nodes
 * (`DOM.getBoxModel` failure or an all-zero box). Indentation is two spaces per
 * EMITTED depth level (not DOM depth). `ref_N` is a per-call 1-based counter —
 * refs are transient handles bound to a single snapshot.
 *
 * Filter buckets mirror a11y-tree.ts exactly:
 *   'interactive' — buttons, links, form controls + headings (always emitted)
 *   'default'     — 'interactive' plus ARIA landmarks
 *   'all'         — anything with a role OR a non-empty accessible name
 *
 * Bounds note: `DOM.getBoxModel` returns quads in CSS pixels relative to the
 * top-level layout viewport — the same coordinate space `getBoundingClientRect`
 * yields in the top frame. We only extract the TOP frame here (frame=0), so no
 * per-iframe offset translation is needed; the JS-walker fallback still handles
 * the multi-frame case.
 */

import type { DebuggerManager } from './debugger-manager'
import type { AXNode } from './cdp-types'

export type SnapshotFilter = 'interactive' | 'all' | 'default'

/** Top frame id. CDP getBoxModel coords are already top-page CSS pixels. */
const TOP_FRAME_ID = 0

// ── Role buckets — identical token vocabulary to a11y-tree.ts ──────────────

/** Interactive roles for the 'interactive' filter. Headings added separately. */
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

/** Landmark roles added by the 'default' filter (on top of interactive). */
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

/** Roles always emitted because they anchor reading position. */
const ALWAYS_EMIT_ROLES = new Set<string>(['heading'])

/**
 * Normalise a Chrome AX role string to the ARIA token vocabulary the JS walker
 * (and {@link roleLabel}) speak. Chrome's AX role values are mostly already the
 * ARIA tokens, with a handful of carve-outs:
 *   - 'StaticText' / 'InlineTextBox' → 'text'  (the generic Text label, only
 *     emitted under filter='all'; matches a11y-tree.ts's role ?? 'text').
 *   - 'image' is already the ARIA token; roleLabel maps it (and the legacy
 *     'img') to 'Image'.
 *   - 'none' / 'presentation' / 'generic' carry no semantic — dropped to '' so
 *     they behave like the JS walker's roleless nodes (named-only under 'all').
 * Everything else is lower-cased so set membership matches.
 */
function normalizeAxRole(raw: string | undefined): string {
  if (!raw) return ''
  const r = raw.trim()
  if (r.length === 0) return ''
  if (r === 'StaticText' || r === 'InlineTextBox' || r === 'text') return 'text'
  // Roles that carry no actionable/landmark semantic — treat as roleless so
  // they only surface (as Text) under filter='all' when they have a name.
  // 'RootWebArea'/'WebArea' are Chrome-AX document containers with no ARIA
  // token and no JS-walker counterpart (the walker starts at <body>, which is
  // roleless); folding them to '' keeps the root from emitting a spurious
  // 'Rootwebarea: <title>' line the server has never seen — see also the
  // root-suppression in selectNodes().
  if (
    r === 'none' ||
    r === 'presentation' ||
    r === 'generic' ||
    r === 'GenericContainer' ||
    r === 'RootWebArea' ||
    r === 'WebArea'
  ) {
    return ''
  }
  return r.toLowerCase()
}

/** Capitalise role for output: 'textbox' → 'Textbox'. 'img'/'image' → 'Image'. */
function roleLabel(role: string): string {
  if (role === 'img' || role === 'image') return 'Image'
  if (role === 'text') return 'Text'
  return role.charAt(0).toUpperCase() + role.slice(1)
}

/** Collapse whitespace runs to single spaces and trim. */
function trim(s: string | null | undefined): string {
  return (s ?? '').replace(/\s+/g, ' ').trim()
}

/**
 * Defensive guard for the frozen line grammar. The server parses the name as
 * everything between ': ' and ' @{'; a name that itself contained a literal
 * ' @{' (or a newline) would desync that parse. trim() already collapses
 * whitespace, so this only neutralises the bbox-marker bigram by swapping the
 * ASCII '@' for a fullwidth '＠' (visible text preserved). Identical to
 * a11y-tree.ts sanitizeName.
 */
function sanitizeName(s: string): string {
  return s.replace(/ @\{/g, ' ＠{')
}

/** Whether a node should be emitted under the given filter — mirrors a11y-tree.ts. */
function shouldEmit(role: string, name: string, filter: SnapshotFilter): boolean {
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

/** Truncate to maxChars on a line boundary, same convention as a11y-tree.ts. */
function truncate(s: string, maxChars: number): string {
  if (s.length <= maxChars) return s
  const head = s.slice(0, maxChars)
  const lastNl = head.lastIndexOf('\n')
  const safeHead = lastNl >= 0 ? head.slice(0, lastNl) : head
  return `${safeHead}\n...TRUNCATED at ${maxChars} bytes`
}

interface Bbox {
  x: number
  y: number
  w: number
  h: number
}

/**
 * Derive an axis-aligned bbox from a `DOM.getBoxModel` content quad.
 * `content` is `[x1,y1, x2,y2, x3,y3, x4,y4]` — four corners. Coords are
 * already top-page CSS pixels (layout viewport), so no frame offset is applied.
 * Returns null for a degenerate (zero-area at origin) box so the caller omits
 * the ` @{…}` segment, matching the JS walker's zero-rect rule.
 */
function bboxFromContentQuad(content: number[] | undefined): Bbox | null {
  if (!content || content.length < 8) return null
  const xs = [content[0], content[2], content[4], content[6]]
  const ys = [content[1], content[3], content[5], content[7]]
  if (xs.some(v => typeof v !== 'number' || !Number.isFinite(v))) return null
  if (ys.some(v => typeof v !== 'number' || !Number.isFinite(v))) return null
  const minX = Math.min(...(xs as number[]))
  const minY = Math.min(...(ys as number[]))
  const maxX = Math.max(...(xs as number[]))
  const maxY = Math.max(...(ys as number[]))
  const x = Math.round(minX)
  const y = Math.round(minY)
  const w = Math.round(maxX - minX)
  const h = Math.round(maxY - minY)
  // Same zero-rect rule as a11y-tree.ts bbox(): a box that is both zero-size
  // AND at the origin is treated as "no rect" (offscreen / not laid out).
  if (w === 0 && h === 0 && x === 0 && y === 0) return null
  return { x, y, w, h }
}

interface EmittedNode {
  role: string
  name: string
  depth: number
  ref: string
  bbox: Bbox | null
}

/** Read an AXValue's string value (role/name) defensively. */
function axString(v: { value?: unknown } | undefined): string {
  if (!v) return ''
  const raw = v.value
  return typeof raw === 'string' ? raw : ''
}

/**
 * Walk the flat AX node list as a tree (childIds give order + structure),
 * deciding per node whether to emit it and at what OUTPUT depth. Output depth
 * advances only when a node is emitted, so indentation reflects the emitted
 * tree — exactly like a11y-tree.ts. Returns the kept nodes in document order
 * along with the set of backendDOMNodeIds whose bounds we still need.
 *
 * `ref_N` is assigned in emission order (1-based), restarting each call.
 */
function selectNodes(
  nodes: AXNode[],
  filter: SnapshotFilter,
): { kept: Array<EmittedNode & { backendId?: number }>; rootIds: string[] } {
  const byId = new Map<string, AXNode>()
  const childOf = new Set<string>()
  for (const n of nodes) {
    if (n && typeof n.nodeId === 'string') byId.set(n.nodeId, n)
  }
  for (const n of nodes) {
    for (const c of n.childIds ?? []) childOf.add(c)
  }
  // Roots = nodes that are nobody's child (typically the single RootWebArea).
  const rootIds = nodes
    .map(n => n.nodeId)
    .filter(id => typeof id === 'string' && !childOf.has(id))

  const kept: Array<EmittedNode & { backendId?: number }> = []
  const visited = new Set<string>()
  let refCounter = 0

  const recurse = (id: string, outDepth: number, isRoot: boolean): void => {
    if (visited.has(id)) return // guard against malformed cyclic childIds
    visited.add(id)
    const node = byId.get(id)
    if (!node) return

    let childOutDepth = outDepth
    if (!node.ignored) {
      const role = normalizeAxRole(axString(node.role))
      const name = trim(axString(node.name))
      // Suppress a roleless ROOT container (e.g. RootWebArea folded to '',
      // whose AX name is the document title): the JS walker starts at <body>
      // and never emits a document-root line, so neither do we — it would
      // otherwise leak as 'Text: <title>' under filter='all'. Roleless
      // NON-root named nodes still surface as Text under 'all', as before.
      const suppressRoot = isRoot && role === ''
      if (!suppressRoot && shouldEmit(role, name, filter)) {
        refCounter += 1
        // Roleless emit only happens under filter='all' for named nodes —
        // surface them under the generic 'text' role (→ 'Text' label), exactly
        // like the JS walker's `role ?? 'text'`.
        const emittedRole = role || 'text'
        kept.push({
          role: emittedRole,
          name,
          depth: outDepth,
          ref: `ref_${refCounter}`,
          bbox: null,
          backendId:
            typeof node.backendDOMNodeId === 'number' ? node.backendDOMNodeId : undefined,
        })
        childOutDepth = outDepth + 1
      }
    }
    for (const c of node.childIds ?? []) recurse(c, childOutDepth, false)
  }

  for (const id of rootIds) recurse(id, 0, true)
  return { kept, rootIds }
}

/** Format one kept node into the frozen line grammar. */
function formatLine(n: EmittedNode): string {
  const indent = '  '.repeat(n.depth)
  const label = roleLabel(n.role)
  const bb = n.bbox ? ` @{${n.bbox.x},${n.bbox.y} ${n.bbox.w}x${n.bbox.h}}` : ''
  const suffix = n.name ? `: ${sanitizeName(n.name)}` : ''
  return `${indent}${label}[ref=${n.ref}, frame=${TOP_FRAME_ID}]${suffix}${bb}`
}

/**
 * Extract the page accessibility tree for `tabId` over CDP and serialise it in
 * the frozen `Role[ref=ref_N, frame=N]: name @{x,y wxh}` format.
 *
 * Requires an ATTACHED {@link DebuggerManager} session for the tab — the caller
 * owns attach/detach. Throws on any CDP failure (the session detached, the
 * commands aren't supported, …) so the caller can fall back to the injected-JS
 * walker. Also throws when the AX tree is empty / yields zero emitted lines, so
 * a degenerate CDP result triggers the same fallback rather than returning a
 * misleadingly blank tree.
 *
 * @param manager attached DebuggerManager (CDP session for `tabId` must exist)
 * @param tabId   target tab id
 * @param filter  'interactive' | 'default' | 'all' (same buckets as the JS walker)
 * @param maxChars hard cap on output length; truncates with the same
 *                 '...TRUNCATED at N bytes' convention
 * @returns the serialised a11y tree text (one element per line)
 */
export async function extractAxTreeViaCdp(
  manager: DebuggerManager,
  tabId: number,
  filter: SnapshotFilter,
  maxChars: number,
): Promise<string> {
  const { nodes } = await manager.send(tabId, 'Accessibility.getFullAXTree', {})
  if (!Array.isArray(nodes) || nodes.length === 0) {
    throw new Error('Accessibility.getFullAXTree returned no nodes')
  }

  const { kept } = selectNodes(nodes, filter)
  if (kept.length === 0) {
    throw new Error('CDP a11y tree produced zero emittable nodes')
  }

  // Attach bounds. One DOM.getBoxModel per kept node keyed by backendDOMNodeId.
  // A failure (node detached, no layout box) leaves bbox=null → the ` @{…}`
  // segment is omitted, exactly like a zero-rect node in the JS walker.
  await Promise.all(
    kept.map(async n => {
      if (typeof n.backendId !== 'number') return
      try {
        const { model } = await manager.send(tabId, 'DOM.getBoxModel', {
          backendNodeId: n.backendId,
        })
        n.bbox = bboxFromContentQuad(model?.content)
      } catch {
        n.bbox = null
      }
    }),
  )

  const lines = kept.map(formatLine)
  return truncate(lines.join('\n'), maxChars)
}
