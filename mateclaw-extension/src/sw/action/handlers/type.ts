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
  /**
   * When true, SELECT-ALL + DELETE the focused field before typing, so a
   * re-type REPLACES existing content instead of appending. Without this, an
   * agent retry types "openclaw" into a box that already holds "openclaw" →
   * "openclawopenclaw". Production (sw/index.ts) sets this true; defaults false
   * so existing unit tests keep their exact event sequences.
   */
  clearFirst?: boolean
}

export interface KeyDescriptor {
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
 *      a. send Input.dispatchKeyEvent { type: 'keyDown', key, code, vk, NO text }
 *      b. send Input.dispatchKeyEvent { type: 'char', text: char }  ← the ONLY
 *         event that carries `text`; this is what inserts the character.
 *      c. send Input.dispatchKeyEvent { type: 'keyUp', key, code, vk, NO text }
 *      d. wait keystrokeIntervalMs() (default: log-normal ~70ms)
 *   4. return Success with { chars_typed: text.length }.
 *
 * CRITICAL — why text is on `char` only: in CDP, a `keyDown` whose `text` field
 * is non-empty ALSO inserts the character (Chrome treats it as a text-producing
 * key), and the `char` event inserts it again → every character is typed TWICE
 * ("openclaw" → "ooppeennccllaaww"). Putting `text` exclusively on the `char`
 * event yields exactly one insertion while keyDown/keyUp still fire so the
 * page's keydown/keyup listeners and control keys (Enter submits, Tab moves
 * focus, Backspace deletes) work. Control chars (\n,\t,\b) skip the `char`
 * event entirely — they don't produce inserted text; their keyDown drives the
 * behavior.
 */
export const typeHandler = (deps: TypeHandlerDeps): ActionHandler<TypeParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const keystrokeIntervalMs = deps.keystrokeIntervalMs ?? (() => logNormalMs(70, 0.4, 40, 120, random))

  return async (tabId, params, deadlineMs) => {
    const startedAt = clock()
    // LLMs frequently emit a literal two-char escape "\n" (backslash + n) when
    // they mean "press Enter to submit" — the JSON arrives as "...\\n" and types
    // a literal `\` and `n` into the field (observed: a Douyin search box left
    // showing `openclaw\n`, never submitted). Normalize a TRAILING literal `\n`
    // (and the rarer `\r\n` / `\t`) to the real control char so it drives the
    // key behavior (Enter/Tab) instead of being typed. Only the trailing one is
    // touched, so literal backslashes mid-text are preserved.
    const normalizedText = normalizeTrailingEscape(params.text)
    const chars = Array.from(normalizedText)

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

      // Replace existing field content (select-all + delete) so a re-type does
      // not append. Only when there's something to type and the caller opted in.
      if (deps.clearFirst && chars.length > 0) {
        await clearFocusedField(deps.debugger, tabId)
      }

      for (const char of chars) {
        const descriptor = describeKey(char)
        // keyDown/keyUp carry NO text (text on keyDown would double-insert).
        await dispatchKeyEvent(deps.debugger, tabId, 'keyDown', descriptor)
        // Only printable chars get a `char` event (the sole text insertion).
        // Control keys (Enter/Tab/Backspace) act via their keyDown alone.
        if (isPrintable(char)) {
          await dispatchKeyEvent(deps.debugger, tabId, 'char', descriptor)
        }
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

export async function dispatchKeyEvent(
  debug: DebuggerManager,
  tabId: number,
  type: 'keyDown' | 'char' | 'keyUp',
  descriptor: KeyDescriptor,
): Promise<void> {
  // `text` is sent ONLY on the 'char' event. A keyDown/keyUp carrying text
  // would insert the character a second time (the doubling bug). keyDown/keyUp
  // still carry key/code/virtual-key-code so site keydown/keyup handlers and
  // control keys behave correctly.
  const withText = type === 'char'
  await debug.send(tabId, 'Input.dispatchKeyEvent', {
    type,
    text: withText ? descriptor.text : '',
    unmodifiedText: withText ? descriptor.text : '',
    key: descriptor.key,
    code: descriptor.code,
    windowsVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    nativeVirtualKeyCode: descriptor.windowsVirtualKeyCode,
    modifiers: 0,
  })
}

/** Printable = produces inserted text (everything except the control keys we
 *  special-case in describeKey). Those drive behavior via their keyDown. */
function isPrintable(char: string): boolean {
  return char !== '\n' && char !== '\t' && char !== '\b'
}

/**
 * If `text` ends with a LITERAL escape sequence (the two/three characters
 * `\n`, `\r\n`, or `\t` — backslash + letter, NOT the control char itself),
 * replace that trailing sequence with the real control char so the type
 * handler presses Enter/Tab instead of typing a stray backslash. A real
 * trailing newline is left as-is (already an Enter). Only the trailing escape
 * is converted, so a backslash earlier in the text is preserved verbatim.
 */
function normalizeTrailingEscape(text: string): string {
  if (text.endsWith('\\r\\n')) return text.slice(0, -4) + '\n'
  if (text.endsWith('\\n')) return text.slice(0, -2) + '\n'
  if (text.endsWith('\\t')) return text.slice(0, -2) + '\t'
  return text
}

/**
 * Select-all (Ctrl+A) then Delete the focused field, so a subsequent type
 * REPLACES rather than appends. Uses debug.send directly (not dispatchKeyEvent)
 * because Ctrl+A needs the Control modifier (CDP modifier bit 2). No `text` on
 * any event — these are control chords, not text input.
 */
async function clearFocusedField(debug: DebuggerManager, tabId: number): Promise<void> {
  const CTRL = 2 // CDP modifiers bitmask: Alt=1, Ctrl=2, Meta=4, Shift=8
  const send = (type: 'keyDown' | 'keyUp', key: string, code: string, vk: number, modifiers: number) =>
    debug.send(tabId, 'Input.dispatchKeyEvent', {
      type, key, code,
      windowsVirtualKeyCode: vk,
      nativeVirtualKeyCode: vk,
      modifiers,
      text: '',
      unmodifiedText: '',
    })
  await send('keyDown', 'a', 'KeyA', 65, CTRL)
  await send('keyUp', 'a', 'KeyA', 65, CTRL)
  await send('keyDown', 'Delete', 'Delete', 46, 0)
  await send('keyUp', 'Delete', 'Delete', 46, 0)
}

export function describeKey(char: string): KeyDescriptor {
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
