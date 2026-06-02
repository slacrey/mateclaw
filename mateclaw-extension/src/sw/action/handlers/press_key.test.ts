import { describe, expect, it, vi } from 'vitest'
import type { DebuggerManager } from '../../debugger-manager'
import { pressKeyHandler } from './press_key'

function fakeDebugger() {
  const sent: Array<{ tabId: number; method: string; params: any }> = []
  const debuggerStub = {
    attach: vi.fn(async () => {}),
    detach: vi.fn(async () => {}),
    send: vi.fn(async (tabId: number, method: string, params: any) => {
      sent.push({ tabId, method, params })
      return {}
    }),
    isAttached: () => true,
  } as unknown as DebuggerManager

  return { debuggerStub, sent }
}

function keyParams(sent: Array<{ tabId: number; method: string; params: any }>) {
  return sent.filter(entry => entry.method === 'Input.dispatchKeyEvent').map(entry => entry.params)
}

describe('press_key handler', () => {
  it('sends only rawKeyDown/keyUp for x, without char insertion or clear prologue', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = pressKeyHandler({ debugger: debuggerStub })

    const result = await handler(42, { key: 'x' }, 5000)

    expect(result.ok).toBe(true)
    expect(keyParams(sent).map(params => [params.type, params.text, params.key, params.code])).toEqual([
      ['rawKeyDown', '', 'x', 'KeyX'],
      ['keyUp', '', 'x', 'KeyX'],
    ])
  })

  it('maps Enter to control-key keyDown/keyUp', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = pressKeyHandler({ debugger: debuggerStub })

    await handler(42, { key: 'Enter' }, 5000)

    expect(keyParams(sent).map(params => [params.type, params.key, params.windowsVirtualKeyCode])).toEqual([
      ['rawKeyDown', 'Enter', 13],
      ['keyUp', 'Enter', 13],
    ])
  })
})
