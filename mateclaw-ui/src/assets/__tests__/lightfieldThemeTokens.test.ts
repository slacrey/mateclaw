// @vitest-environment node
// @ts-expect-error Vitest runs this in node, while the app tsconfig intentionally omits Node types.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../main.css', import.meta.url), 'utf8')
const chatSurfaceFiles = [
  '../../views/ChatConsole.vue',
  '../../components/chat/ConversationSidebar.vue',
  '../../components/chat/ChatInput.vue',
  '../../components/chat/MessageBubble.vue',
] as const

const chatSurfaceSource = chatSurfaceFiles
  .map((file) => readFileSync(new URL(file, import.meta.url), 'utf8'))
  .join('\n')

describe('lightfield theme tokens', () => {
  it('uses the approved cold-blue brand palette instead of the old warm palette', () => {
    expect(css).toContain(':root')
    expect(css).toContain('--mc-primary: #476CFF')
    expect(css).toContain('--mc-primary-hover: #3455F4')
    expect(css).toContain('--mc-accent: #19BFD1')
    expect(css).toContain('--mc-bg: #eef6ff')
    expect(css).not.toContain('--mc-primary: #d96d46')
    expect(css).not.toContain('--mc-bg: #f6f1ea')
  })

  it('keeps state colors distinct so the interface does not become one-note blue', () => {
    expect(css).toContain('--mc-success: #22C55E')
    expect(css).toContain('--mc-warning: #F59E0B')
    expect(css).toContain('--mc-danger: #EF476F')
    expect(css).toContain('--mc-info: #476CFF')
  })

  it('defines lightfield surface and sidebar tokens used by the layout shell', () => {
    expect(css).toContain('--mc-lightfield-grid')
    expect(css).toContain('--mc-sidebar-bg: rgba(8, 22, 66, 0.88)')
    expect(css).toContain('--mc-sidebar-active: linear-gradient(135deg, #476CFF, #6E8BFF)')
  })

  it('keeps chat console surfaces on cold lightfield fallbacks', () => {
    expect(chatSurfaceSource).not.toMatch(/217,\s*(?:109|119),\s*(?:70|87)/)
    expect(chatSurfaceSource).not.toMatch(/#(?:D97757|d97757|d96d46|bb4f27)\b/)
    expect(chatSurfaceSource).not.toMatch(/border(?:-[^:]+)?:\s*var\(--mc-user-bubble-bg/)
    expect(chatSurfaceSource).not.toMatch(/color:\s*var\(--mc-user-bubble-bg/)
  })
})
