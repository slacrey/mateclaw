import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError } from '../ActionExecutor'
import { typeHandler } from './type'

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

async function flushPromises(times: number): Promise<void> {
  for (let i = 0; i < times; i++) {
    await Promise.resolve()
  }
}

describe('type handler', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('types "abc" — text ONLY on char events (keyDown/keyUp empty) so each char inserts once', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: 'abc' }, 5000)

    expect(result.ok).toBe(true)
    // Regression guard for the "openclaw"→"ooppeennccllaaww" doubling: a keyDown
    // carrying `text` inserts the char, and so does the `char` event — only the
    // `char` event may carry text. keyDown/keyUp still fire (with key set) for
    // site listeners + control keys, but with empty text.
    expect(keyParams(sent).map(params => [params.type, params.text, params.key])).toEqual([
      ['keyDown', '', 'a'],
      ['char', 'a', 'a'],
      ['keyUp', '', 'a'],
      ['keyDown', '', 'b'],
      ['char', 'b', 'b'],
      ['keyUp', '', 'b'],
      ['keyDown', '', 'c'],
      ['char', 'c', 'c'],
      ['keyUp', '', 'c'],
    ])
  })

  it('Enter (\\n) sends keyDown + keyUp only (no char event — control keys do not insert text)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '\n' }, 5000)

    expect(keyParams(sent).map(params => [params.type, params.key, params.windowsVirtualKeyCode])).toEqual([
      ['keyDown', 'Enter', 13],
      ['keyUp', 'Enter', 13],
    ])
  })

  it('with focus_target sends a left click first, then keys', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({
      debugger: debuggerStub,
      keystrokeIntervalMs: () => 0,
    })

    await handler(42, { text: 'a', focus_target: { x: 30, y: 40 } }, 5000)

    expect(sent[0]).toMatchObject({
      method: 'Input.dispatchMouseEvent',
      params: { type: 'mousePressed', x: 30, y: 40, button: 'left', clickCount: 1 },
    })
    expect(sent[1]).toMatchObject({
      method: 'Input.dispatchMouseEvent',
      params: { type: 'mouseReleased', x: 30, y: 40, button: 'left', clickCount: 1 },
    })
    expect(sent[2]).toMatchObject({
      method: 'Input.dispatchKeyEvent',
      params: { type: 'keyDown', text: '', key: 'a' },
    })
  })

  it('without focus_target sends only key events', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: 'ab' }, 5000)

    expect(sent.map(entry => entry.method)).toEqual(Array.from({ length: 6 }, () => 'Input.dispatchKeyEvent'))
  })

  it('returns Success with chars_typed = text.length', async () => {
    const { debuggerStub } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, clock: () => 50_000, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: 'hello' }, 5000)

    expect(result).toEqual({
      ok: true,
      elapsed_ms: 0,
      payload: { chars_typed: 5 },
    })
  })

  it('empty text returns Success with chars_typed=0 and no CDP calls', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: '' }, 5000)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual({ chars_typed: 0 })
    }
    expect(sent).toHaveLength(0)
  })

  it('uses injected clock+random for deterministic keystroke intervals', async () => {
    vi.useFakeTimers()
    const { debuggerStub, sent } = fakeDebugger()
    const clock = vi.fn(() => 20_000)
    const random = vi.fn(() => 0.5)
    const handler = typeHandler({ debugger: debuggerStub, clock, random })

    const pending = handler(42, { text: 'ab' }, 5000)

    await flushPromises(8)
    expect(sent).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(43)
    expect(sent).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises(8)
    expect(sent).toHaveLength(6)
    await vi.advanceTimersByTimeAsync(44)
    const result = await pending

    expect(result.ok).toBe(true)
    expect(sent).toHaveLength(6)
    expect(clock).toHaveBeenCalled()
    expect(random).toHaveBeenCalled()
  })

  it('SESSION_DETACHED during typing throws ActionFailureError', async () => {
    const { debuggerStub } = fakeDebugger()
    vi.mocked(debuggerStub.send).mockRejectedValueOnce(new SessionDetachedError(42, 'target_closed'))
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await expect(handler(42, { text: 'a' }, 5000)).rejects.toMatchObject({
      name: 'ActionFailureError',
      code: 'SESSION_DETACHED',
      retryable: true,
    } satisfies Partial<ActionFailureError>)
  })

  it('handles unicode characters (text includes 你好)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '你好' }, 5000)

    expect(keyParams(sent).filter(params => params.type === 'char').map(params => params.text)).toEqual(['你', '好'])
  })

  it('handles special keys via Enter / Tab / Backspace literal in text', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '\n\t\b' }, 5000)

    // Control keys emit keyDown + keyUp ONLY (no char event — they don't insert
    // text; their keyDown drives submit/focus/delete). Text is empty on both
    // (text would belong to a char event, which we don't send for these).
    expect(keyParams(sent).map(params => [params.type, params.text, params.key, params.code])).toEqual([
      ['keyDown', '', 'Enter', 'Enter'],
      ['keyUp', '', 'Enter', 'Enter'],
      ['keyDown', '', 'Tab', 'Tab'],
      ['keyUp', '', 'Tab', 'Tab'],
      ['keyDown', '', 'Backspace', 'Backspace'],
      ['keyUp', '', 'Backspace', 'Backspace'],
    ])
  })
})
