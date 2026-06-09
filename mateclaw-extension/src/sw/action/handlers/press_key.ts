import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { PressKeyParams } from '../types'
import { describeKey, type KeyDescriptor } from './type'

export interface PressKeyHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
}

export const pressKeyHandler = (deps: PressKeyHandlerDeps): ActionHandler<PressKeyParams> => {
  const clock = deps.clock ?? Date.now

  return async (tabId, params) => {
    const startedAt = clock()
    const key = normalizeKey(params.key)
    const descriptor = descriptorForPressKey(key)

    try {
      await deps.debugger.attach(tabId)
      await dispatchPressKeyEvent(deps.debugger, tabId, 'rawKeyDown', descriptor)
      await dispatchPressKeyEvent(deps.debugger, tabId, 'keyUp', descriptor)
      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { key },
      }
    } catch (err) {
      if (err instanceof SessionDetachedError) {
        throw new ActionFailureError('SESSION_DETACHED', err.message, true)
      }
      throw err
    }
  }
}

function normalizeKey(raw: string): string {
  const key = String(raw ?? '').trim()
  if (!key) {
    throw new ActionFailureError('HANDLER_ERROR', 'key is required', false)
  }
  if (key === 'Enter') return '\n'
  if (key === 'Tab') return '\t'
  if (key === 'Backspace') return '\b'
  if (SUPPORTED_NAMED_KEYS.has(key)) return key
  if (Array.from(key).length !== 1) {
    throw new ActionFailureError('HANDLER_ERROR', `unsupported key '${key}'`, false)
  }
  return key
}

function descriptorForPressKey(key: string): KeyDescriptor {
  const named = NAMED_KEY_DESCRIPTORS[key]
  if (named) return named
  return describeKey(key)
}

const SUPPORTED_NAMED_KEYS = new Set(['PageDown', 'PageUp', 'End', 'Home', 'ArrowDown', 'ArrowUp'])

const NAMED_KEY_DESCRIPTORS: Record<string, KeyDescriptor> = {
  PageDown: { text: '', key: 'PageDown', code: 'PageDown', windowsVirtualKeyCode: 34 },
  PageUp: { text: '', key: 'PageUp', code: 'PageUp', windowsVirtualKeyCode: 33 },
  End: { text: '', key: 'End', code: 'End', windowsVirtualKeyCode: 35 },
  Home: { text: '', key: 'Home', code: 'Home', windowsVirtualKeyCode: 36 },
  ArrowDown: { text: '', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowUp: { text: '', key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 },
}

async function dispatchPressKeyEvent(
  debug: DebuggerManager,
  tabId: number,
  type: 'rawKeyDown' | 'keyUp',
  descriptor: KeyDescriptor,
): Promise<void> {
  // Use rawKeyDown for real keyboard shortcuts. Some rich sites bind shortcuts
  // to the browser's raw key path and ignore synthetic text-oriented keyDown.
  // Keep text empty so this never inserts "x" into a focused search/comment box.
  await debug.send(tabId, 'Input.dispatchKeyEvent', {
    type,
    text: '',
    unmodifiedText: '',
    key: descriptor.key,
    code: descriptor.code,
    windowsVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    nativeVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    modifiers: 0,
  })
}
