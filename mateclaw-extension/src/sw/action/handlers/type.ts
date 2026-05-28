import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { TypeParams } from '../types'
import { clickHandler } from './click'

export interface TypeHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Per-keystroke delay (default: log-normal ~40-120ms - human typing) */
  keystrokeIntervalMs?: () => number
}

interface KeyDescriptor {
  readonly text: string
  readonly key: string
  readonly code?: string
  readonly windowsVirtualKeyCode?: number
}

/**
 * type handler.
 *
 * Flow:
 *   1. debugger.attach(tabId).
 *   2. If params.focus_target -> click that point first (single left click).
 *   3. For each char in params.text:
 *      a. send Input.dispatchKeyEvent { type: 'keyDown', text: char, key: char, ... }
 *      b. send Input.dispatchKeyEvent { type: 'char', text: char, key: char, ... }
 *         (Some characters require the 'char' event for IME - keep it simple
 *         and always send keyDown + char + keyUp.)
 *      c. send Input.dispatchKeyEvent { type: 'keyUp', text: char, key: char, ... }
 *      d. wait keystrokeIntervalMs() (default: log-normal ~70ms)
 *   4. return Success with { chars_typed: text.length }.
 */
export const typeHandler = (deps: TypeHandlerDeps): ActionHandler<TypeParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const keystrokeIntervalMs = deps.keystrokeIntervalMs ?? (() => logNormalMs(70, 0.4, 40, 120, random))

  return async (tabId, params, deadlineMs) => {
    const startedAt = clock()
    const chars = Array.from(params.text)

    try {
      await deps.debugger.attach(tabId)

      if (params.focus_target) {
        await clickHandler({
          debugger: deps.debugger,
          clock,
          random,
          pressHoldMs: () => 0,
        })(tabId, {
          x: params.focus_target.x,
          y: params.focus_target.y,
          button: 'left',
          click_count: 1,
        }, deadlineMs)
      }

      for (const char of chars) {
        const descriptor = describeKey(char)
        await dispatchKeyEvent(deps.debugger, tabId, 'keyDown', descriptor)
        await dispatchKeyEvent(deps.debugger, tabId, 'char', descriptor)
        await dispatchKeyEvent(deps.debugger, tabId, 'keyUp', descriptor)
        await sleep(keystrokeIntervalMs())
      }

      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { chars_typed: params.text.length },
      }
    } catch (err) {
      if (err instanceof SessionDetachedError) {
        throw new ActionFailureError('SESSION_DETACHED', err.message, true)
      }
      throw err
    }
  }
}

async function dispatchKeyEvent(
  debug: DebuggerManager,
  tabId: number,
  type: 'keyDown' | 'char' | 'keyUp',
  descriptor: KeyDescriptor,
): Promise<void> {
  await debug.send(tabId, 'Input.dispatchKeyEvent', {
    type,
    text: descriptor.text,
    key: descriptor.key,
    code: descriptor.code,
    windowsVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    nativeVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    unmodifiedText: descriptor.text,
    modifiers: 0,
  })
}

function describeKey(char: string): KeyDescriptor {
  if (char === '\n') {
    return { text: char, key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 }
  }
  if (char === '\t') {
    return { text: char, key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 }
  }
  if (char === '\b') {
    return { text: char, key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 }
  }

  return {
    text: char,
    key: char,
    code: codeForPrintableAscii(char),
    windowsVirtualKeyCode: virtualKeyCodeForPrintableAscii(char),
  }
}

function codeForPrintableAscii(char: string): string | undefined {
  if (/^[a-z]$/.test(char)) return `Key${char.toUpperCase()}`
  if (/^[A-Z]$/.test(char)) return `Key${char}`
  if (/^[0-9]$/.test(char)) return `Digit${char}`
  return PUNCTUATION_CODE[char]
}

function virtualKeyCodeForPrintableAscii(char: string): number | undefined {
  if (/^[a-zA-Z]$/.test(char)) return char.toUpperCase().charCodeAt(0)
  if (/^[0-9]$/.test(char)) return char.charCodeAt(0)
  return PUNCTUATION_VK[char]
}

const PUNCTUATION_CODE: Record<string, string> = {
  ' ': 'Space',
  '-': 'Minus',
  '=': 'Equal',
  '[': 'BracketLeft',
  ']': 'BracketRight',
  '\\': 'Backslash',
  ';': 'Semicolon',
  "'": 'Quote',
  ',': 'Comma',
  '.': 'Period',
  '/': 'Slash',
  '`': 'Backquote',
}

const PUNCTUATION_VK: Record<string, number> = {
  ' ': 32,
  '-': 189,
  '=': 187,
  '[': 219,
  ']': 221,
  '\\': 220,
  ';': 186,
  "'": 222,
  ',': 188,
  '.': 190,
  '/': 191,
  '`': 192,
}

function logNormalMs(
  medianMs: number,
  sigma: number,
  minMs: number,
  maxMs: number,
  random: () => number,
): number {
  const u1 = Math.max(random(), 1e-12)
  const u2 = random()
  const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  const sample = Math.exp(Math.log(medianMs) + sigma * z)
  return clamp(Math.round(sample), minMs, maxMs)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function sleep(ms: number): Promise<void> {
  if (!Number.isFinite(ms) || ms <= 0) return Promise.resolve()
  return new Promise(resolve => setTimeout(resolve, ms))
}
