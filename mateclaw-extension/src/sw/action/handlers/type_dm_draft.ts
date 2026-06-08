import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { TypeDmDraftParams } from '../types'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'

export interface TypeDmDraftHandlerDeps {
  debugger: DebuggerManager
  chrome?: typeof globalThis.chrome
}

export const typeDmDraftHandler = (
  deps: TypeDmDraftHandlerDeps = {},
): ActionHandler<TypeDmDraftParams> => {
  return async (tabId, params, _deadlineMs) => {
    const text = String(params?.text || '').trim()
    if (!text) {
      throw new ActionFailureError('HANDLER_ERROR', 'type_dm_draft text is required', false)
    }
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: typeDouyinDmDraftInPage,
      args: [text],
    })
    const payload = results?.[0]?.result as
      | { ok?: boolean; reason?: string; draftTyped?: boolean; target?: string }
      | undefined
    if (payload?.ok !== true || payload.draftTyped !== true) {
      const cdp = await typeDmDraftByCdp(deps.debugger, tabId, text)
      if (cdp.ok === true) {
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            draftTyped: true,
            text,
            target: cdp.target || 'dm_cdp_insert_text',
          },
        }
      }
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `${payload?.reason || 'dm draft was not observed after typing'}; cdp=${cdp.reason || 'failed'}`,
        false,
      )
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        draftTyped: true,
        text,
        target: payload.target || 'dm_editable',
      },
    }
  }
}

async function typeDmDraftByCdp(
  debug: DebuggerManager,
  tabId: number,
  text: string,
): Promise<{ ok: boolean; reason?: string; target?: string }> {
  try {
    const point = await findDmInputClickPoint(tabId)
    if (!point) return { ok: false, reason: 'dm_input_click_point_not_found' }
    await debug.attach(tabId)
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mousePressed',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mouseReleased',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.insertText', { text })
    await sleep(180)
    const observed = await draftVisibleInPage(tabId, text)
    return {
      ok: observed,
      reason: observed ? undefined : `draft_not_visible_after_cdp_insert@${Math.round(point.x)},${Math.round(point.y)}`,
      target: `cdp@${Math.round(point.x)},${Math.round(point.y)}`,
    }
  } catch (error) {
    if (error instanceof SessionDetachedError) {
      return { ok: false, reason: error.message }
    }
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
}

function typeDouyinDmDraftInPage(text: string): { ok: boolean; reason?: string; draftTyped?: boolean; target?: string } {
  if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) {
    return { ok: false, reason: 'not_douyin_page' }
  }
  const pageText = document.body?.innerText || document.body?.textContent || ''
  if (!/私信|发送消息|输入消息|消息/u.test(pageText)) {
    return { ok: false, reason: 'dm_context_not_visible' }
  }
  const target = findDmEditable()
  if (!target) {
    return { ok: false, reason: 'dm_editable_not_found' }
  }
  target.scrollIntoView({ block: 'center', inline: 'center' })
  target.focus()

  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    const setter = Object.getOwnPropertyDescriptor(
      target instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      'value',
    )?.set
    if (setter) setter.call(target, text)
    else target.value = text
    target.dispatchEvent(new InputEvent('input', {
      bubbles: true,
      composed: true,
      data: text,
      inputType: 'insertReplacementText',
    }))
    target.dispatchEvent(new Event('change', { bubbles: true, composed: true }))
  } else {
    placeCaretAtEnd(target)
    const selected = selectEditableContent(target)
    if (selected) {
      document.execCommand?.('delete', false)
    } else {
      target.textContent = ''
      placeCaretAtEnd(target)
    }
    document.execCommand?.('insertText', false, text)
    if (!(editableText(target).includes(text))) {
      target.textContent = text
      target.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        composed: true,
        data: text,
        inputType: 'insertReplacementText',
      }))
    }
  }

  const typed = editableText(target).includes(text) ||
    findDmEditableText().includes(text) ||
    clean(document.body?.innerText || document.body?.textContent || '').includes(text)
  return {
    ok: typed,
    draftTyped: typed,
    reason: typed ? undefined : 'draft_text_not_visible_in_editable',
    target: targetDescription(target),
  }
}

function findDmEditable(): HTMLElement | null {
  const selectors = [
    'textarea',
    'input',
    '[contenteditable="true"]',
    '[contenteditable=""]',
    '[contenteditable="plaintext-only"]',
    '[role="textbox"]',
    '[data-slate-editor="true"]',
    '.ProseMirror',
    '.DraftEditor-editorContainer [contenteditable]',
    '[placeholder*="消息"]',
    '[placeholder*="私信"]',
    '[placeholder*="发送"]',
    '[aria-label*="消息"]',
    '[aria-label*="私信"]',
  ].join(',')
  const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
  const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
  return Array.from(document.querySelectorAll<HTMLElement>(selectors))
    .map((el, index) => ({ el, index, rect: el.getBoundingClientRect(), text: elementText(el) }))
    .map(item => {
      const editable = editableRoot(item.el)
      return editable
        ? { ...item, el: editable, rect: editable.getBoundingClientRect(), text: elementText(editable) }
        : { ...item, el: null }
    })
    .filter((item): item is { el: HTMLElement; index: number; rect: DOMRect; text: string } => item.el !== null)
    .filter(item => isEditable(item.el))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.top >= Math.max(80, viewportH * 0.22))
    .filter(item => item.rect.left >= viewportW * 0.52)
    .filter(item => !item.text.includes('搜索'))
    .sort((a, b) => scoreEditable(b) - scoreEditable(a) || a.index - b.index)[0]?.el ?? null
}

function editableRoot(el: HTMLElement): HTMLElement | null {
  if (isEditable(el)) return el
  const closest = el.closest<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  if (closest && isEditable(closest)) return closest
  const nested = el.querySelector<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  return nested && isEditable(nested) ? nested : null
}

function isEditable(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return true
  if (el.isContentEditable) return true
  if ((el.getAttribute('role') || '').toLowerCase() === 'textbox') return true
  if (el.getAttribute('data-slate-editor') === 'true') return true
  return el.classList.contains('ProseMirror')
}

function scoreEditable(item: { el: HTMLElement; rect: DOMRect; text: string }): number {
  let score = 0
  const marker = elementText(item.el)
  if (item.el instanceof HTMLTextAreaElement) score += 120
  if (item.el.isContentEditable) score += 110
  if ((item.el.getAttribute('role') || '').toLowerCase() === 'textbox') score += 80
  if (/消息|私信|发送/u.test(marker)) score += 100
  if (item.rect.top > (window.innerHeight || 1) * 0.55) score += 60
  if (item.rect.width > 160) score += 30
  return score
}

function findDmEditableText(): string {
  return Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
    .map(editableText)
    .join('\n')
}

function editableText(el: HTMLElement): string {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    return el.value || ''
  }
  return el.innerText || el.textContent || ''
}

function elementText(el: HTMLElement): string {
  return `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
}

function selectEditableContent(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    el.select()
    return true
  }
  const selection = window.getSelection()
  if (!selection) return false
  const range = document.createRange()
  range.selectNodeContents(el)
  selection.removeAllRanges()
  selection.addRange(range)
  return true
}

function placeCaretAtEnd(el: HTMLElement): void {
  el.focus()
  const selection = window.getSelection()
  if (!selection || el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}

function clean(text: string): string {
  return String(text || '').replace(/\s+/g, '')
}

function targetDescription(el: HTMLElement): string {
  return `${el.tagName.toLowerCase()}${el.getAttribute('role') ? `[role=${el.getAttribute('role')}]` : ''}`
}

async function findDmInputClickPoint(tabId: number): Promise<{ x: number; y: number } | null> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: () => {
      if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) return null
      const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
      const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
      const selectors = [
        'textarea',
        'input',
        '[contenteditable="true"]',
        '[contenteditable=""]',
        '[contenteditable="plaintext-only"]',
        '[role="textbox"]',
        '[data-slate-editor="true"]',
        '.ProseMirror',
        '[placeholder*="消息"]',
        '[placeholder*="私信"]',
        '[placeholder*="发送"]',
        '[aria-label*="消息"]',
        '[aria-label*="私信"]',
        'div',
      ].join(',')
      const candidates = Array.from(document.querySelectorAll<HTMLElement>(selectors))
        .map((el, index) => {
          const rect = el.getBoundingClientRect()
          const text = `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
          const editable = el instanceof HTMLInputElement ||
            el instanceof HTMLTextAreaElement ||
            el.isContentEditable ||
            (el.getAttribute('role') || '').toLowerCase() === 'textbox' ||
            el.getAttribute('data-slate-editor') === 'true' ||
            el.classList.contains('ProseMirror')
          let score = 0
          if (editable) score += 200
          if (/消息|私信|发送|请输入|聊/u.test(text)) score += 160
          if (rect.left >= viewportW * 0.52) score += 90
          if (rect.top >= viewportH * 0.70) score += 90
          if (rect.width >= 240 && rect.height >= 28 && rect.height <= 160) score += 70
          if (text.includes('搜索') || text.includes('关闭会话') || text.includes('回关')) score -= 300
          return { rect, index, score }
        })
        .filter(item => item.rect.width > 0 && item.rect.height > 0)
        .filter(item => item.rect.left >= viewportW * 0.52)
        .filter(item => item.rect.top >= viewportH * 0.55)
        .filter(item => item.score > 0)
        .sort((a, b) => b.score - a.score || b.rect.top - a.rect.top || a.index - b.index)
      const best = candidates[0]
      if (best) {
        return {
          x: best.rect.left + Math.min(best.rect.width * 0.5, best.rect.width - 24),
          y: best.rect.top + Math.min(best.rect.height * 0.5, best.rect.height - 16),
        }
      }
      return { x: viewportW * 0.84, y: viewportH - 42 }
    },
  })
  const point = result?.result as { x?: number; y?: number } | null | undefined
  return typeof point?.x === 'number' && typeof point?.y === 'number' ? { x: point.x, y: point.y } : null
}

async function draftVisibleInPage(tabId: number, text: string): Promise<boolean> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: (draft: string) => {
      const clean = (value: string) => String(value || '').replace(/\s+/g, '')
      const wanted = clean(draft)
      const editableText = Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
        .map(el => {
          if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el.value || ''
          return el.innerText || el.textContent || ''
        })
        .join('\n')
      return clean(editableText).includes(wanted) || clean(document.body?.innerText || document.body?.textContent || '').includes(wanted)
    },
    args: [text],
  })
  return result?.result === true
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}
