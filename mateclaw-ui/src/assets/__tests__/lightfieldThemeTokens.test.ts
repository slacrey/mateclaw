import { describe, expect, it } from 'vitest'
import css from '../main.css?raw'

describe('lightfield theme tokens', () => {
  it('uses the approved cold-blue brand palette instead of the old warm palette', () => {
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
})
