import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import layout from '../MainLayout.vue?raw'
import { useThemeStore } from '../../../stores/useThemeStore'

describe('appearance preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
    setActivePinia(createPinia())
  })

  it('does not render appearance theme controls in the left sidebar footer', () => {
    expect(layout).not.toContain("t('nav.themeLabel')")
    expect(layout).not.toContain('theme-toggle-row')
    expect(layout).not.toContain('themeStore.setMode')
  })

  it('defaults to light mode when no saved preference exists', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true,
      media: '(prefers-color-scheme: dark)',
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    } as unknown as MediaQueryList)

    const themeStore = useThemeStore()

    expect(themeStore.mode).toBe('light')
    expect(themeStore.isDark).toBe(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('ignores legacy saved dark preferences so users are not stuck without a switch', () => {
    localStorage.setItem('mateclaw-theme', 'dark')

    const themeStore = useThemeStore()

    expect(themeStore.mode).toBe('light')
    expect(themeStore.isDark).toBe(false)
  })
})
